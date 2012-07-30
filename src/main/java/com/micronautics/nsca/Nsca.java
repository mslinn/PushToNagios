/*
 * Copyright 1999,2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License. */

package com.micronautics.nsca;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.annotation.target.param;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

// todo make the docs line up with the finished software
/** Java native equivalent to nsca_send program. It can encrypt and send alerts to the NSCA server. Example usage:
 * <pre>try {
 * 	   Nsca nsca = new Nsca();
 * 	   nsca.setConfigFile("nsca_send_clear.properties");
 * 	   nsca.send("localhost", "5667", "MyApplication", "something bad just happened", 1, 0);
 * } catch (Exception e) {
 * 	   System.out.println(e.getMessage());
 * }</pre>
 * @author <a href="mailto:jarlyons@gmail.com">Jar Lyons</a> Original Java version, packaged with log4j/MDC
 * @author <a href="mailto:mslinn@micronauticsresearch.com">Mike Slinn</a> standalone version */
public class Nsca {
    /* Initialization Vector size */
    private static final int TRANSMITTED_IV_SIZE = 128;

    /* No encryption to Nsca server required */
    public static final int ENCRYPT_NONE = 0;

    /* XOR encryption to Nsca server required */
    public static final int ENCRYPT_XOR = 1;

    /** For internal use; indicates there is no message to send */
    protected static final int NAGIOS_NO_MSG = -1;

    /** Nagios message level for green status */
    public static final int NAGIOS_OK = 0;

    /** Nagios message level for yellow status */
    public static final int NAGIOS_WARN = 1;

    /** Nagios message level for red status */
    public static final int NAGIOS_CRITICAL = 2;

    /** Nagios message level for unknown status */
    public static final int NAGIOS_UNKNOWN = 3;

    private Logger logger = LoggerFactory.getLogger(Nsca.class);

    /** Configuration file; indicates the encryption model to use and the password, in Java properties format */
    private String configFile = null;
    private int _encryptionMethod = ENCRYPT_NONE;

    /** NSCA password (optional and only used with XOR) */
    private String _password = "";

    private int nscaVersion = 3;

    /** Variables associated with the thread pool used for sending to nsca server */
    private int poolSize = 50;
    private int maxPoolSize = 50;
    private ThreadPoolExecutor threadPool = null;
    private final LinkedBlockingQueue queue = new LinkedBlockingQueue(2000);
    private long keepAliveTime = 10;

    /** Optional message to be sent to Nagios when the appender is instantiated. */
    private String startupMsg = "";

    /** Level of optional message to be sent to Nagios when the appender is instantiated. */
    private int startupMsgLevel = NAGIOS_NO_MSG;

    /**
     * Variables associated with delivery timeouts ...
     */
    private int timeout = 5000;
    Thread unsentMessageThread = null;
    private int reportDelayThresholdSeconds = 10;

    /** Nagios host where the nsca server is running */
    private String nscaHost = "localhost";

    /** Nagios port where the nsca server is running */
    private int nscaPort = 5667;

    /** host for this code */
    private final String reportingHost = getHost();

    /** Nagios service name to associate with the messages forwarded to NSCA server */
    private String nscaService = "UNSPECIFIED_SERVICE";


    public Nsca() throws Exception {
        createThreadPool();
    }

    public Nsca(String nscaConfigFileName) throws Exception {
        if (nscaConfigFileName == null)
            return;

        nscaConfigFileName = nscaConfigFileName.trim();
        if (nscaConfigFileName.length() == 0)
            return;

        configFile = nscaConfigFileName;
        configure();
        createThreadPool();
    }

    /** Set the configuration parameters instead of reading them from the config file.
     * @param encryptionMethod The new encryption method (0=None, 1=XOR)
     * @param password must be specified for encryption methods other than None. */
    public Nsca(String host, int port, String service, int encryptionMethod, String password) throws Exception {
        init(host, port, service, encryptionMethod, password);
    }

    /** Set the configuration parameters instead of reading them from the config file; encryption method defaults to none */
    public Nsca(String host, int port, String service) throws Exception {
        init(host, port, service, ENCRYPT_NONE, "");
    }

    /** Push the alert to the nagios server
     * @param msgLevel one of NAGIOS_UNKNOWN, NAGIOS_OK, NAGIOS_WARN, or NAGIOS_CRITICAl
     * @param message up to 256 characters long */
    public void send(int msgLevel, String message) throws Exception {
        logger.debug("send() about to send '" + message + "'");
        if (null == message)
            return;

        NscaSendRunnable nscaSendRunnable = new NscaSendRunnable(msgLevel, message);
        try {
            threadPool.execute(nscaSendRunnable);
        } catch (RejectedExecutionException e) {
            logger.error(e.getMessage());
        }
    }

    public void start() throws Exception {
        if (startupMsg.length()>0)
            send(startupMsgLevel, startupMsg);
        startupMsgLevel = NAGIOS_NO_MSG;
        startupMsg = null;
    }

    public void setStartupMessage(int msgLevel, String msgText) {
        startupMsgLevel = msgLevel;
        startupMsg = msgText;
    }

    /**
     * If no config file specified, the default encryption method and no password is assumed.
     * @return Boolean specifying whether configuration succeeded
     */
    protected void configure() throws Exception {
        Properties props = new Properties();
        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream(configFile);
            props.load(is);
            _encryptionMethod = Integer.parseInt(props.getProperty("encryption_method", "" + ENCRYPT_NONE));
            _password         = props.getProperty("password", "");
            nscaHost          = props.getProperty("nscaHost", "localhost");
            nscaPort          = Integer.parseInt(props.getProperty("nscaPort", "5667"));
            nscaService       = props.getProperty("nscaService", "UNSPECIFIED_SERVICE");
        } catch (IOException ex) {
            System.err.println(ex.getMessage());
            System.exit(-1);
        }
    }

    /** Encrypts the send buffer according the nsca encryption method
     * @param buffer Buffer to be encrypted
     * @param encryptionVector Encryption Initialization Vector
     * @throws Exception for unsupported encryption scheme */
    public void encryptBuffer(int encryptionMethod, byte[] buffer, byte[] encryptionVector) throws Exception {
        switch (encryptionMethod) {
            case ENCRYPT_NONE:
                break;

            case ENCRYPT_XOR:
                /* rotate over encryptionVector received from the server */
                for (int y = 0, x = 0; y < buffer.length; y++, x++) {
                    /* keep rotating over encryptionVector */
                    if (x >= TRANSMITTED_IV_SIZE)
                        x = 0;
                    buffer[y] ^= encryptionVector[x];
                }
                /* rotate over password */
                if (_password != null) {
                    byte[] password = _password.getBytes();
                    for (int y = 0, x = 0; y < buffer.length; y++, x++) {
                        if (x >= password.length)
                            x = 0;
                        buffer[y] ^= password[x];
                    }
                }
                break;

            default:
                throw new Exception("NagiosAppender::encryptBuffer(): unsupported encryption method: " + encryptionMethod);
        }
    }

    private void createThreadPool() {
        threadPool = new ThreadPoolExecutor(poolSize, maxPoolSize, keepAliveTime, TimeUnit.SECONDS, queue);
    }

    private class NscaEvent {
        public int level;
        public String message;

        public NscaEvent(int level, String message) {
            this.level = level;
            this.message = message;
        }
    }

    protected String getHost() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostAddress(); // .getHostName();
        } catch (Exception e) {
            return null;
        }
//        if (hostname.indexOf(".") > 0)
//            hostname = hostname.substring(0, hostname.indexOf("."));
        return hostname;
    }

    private void init(String host, int port, String service, int encryptionMethod, String password) throws Exception {
        if (host==null || host.trim().length()==0)
            throw new Exception("No NSCA host specified");

        if (service==null || service.trim().length()==0)
            throw new Exception("No NSCA service specified");

        this.nscaHost = host;
        this.nscaPort = port;
        this.nscaService = service;

        if (encryptionMethod >= 0)
            _encryptionMethod = encryptionMethod;

        if (password != null) {
            password = password.trim();
            if (password.length() > 0)
                _password = password;
        }

        createThreadPool();
    }

    private class NscaSendRunnable implements Runnable {
        private boolean finished = false;
        private String message;
        private int msgLevel;
        private ArrayList buffer;

        public NscaSendRunnable(int msgLevel, String message) {
            this.message = message;
            this.msgLevel = msgLevel;
        }

        public void run() {
            Socket s = null;
            OutputStream out = null;
            DataInputStream in = null;
            logger.debug("Runnable starting; preparing to send level " + msgLevel + " message '" + message + "' to '" +
                    nscaService + "' service monitor on " + nscaHost + ":" + nscaPort);
            try {
                int count = 0;
                while (count < 3) {
                    count++;
                    s = new Socket();
                    s.setKeepAlive(true);
                    s.setSoTimeout(timeout);
                    s.setTcpNoDelay(false);
                    java.net.InetSocketAddress socketAddress = new InetSocketAddress(nscaHost, nscaPort);
                    s.connect(socketAddress);
                    if (s.isBound())
                        break;
                    else {
                        try {
                            s.close();
                        } catch (Exception e) {
                            logger.debug("Runnable Exception while closing socket: '" + e.getMessage());
                        }
                        s = null;
                    }
                }
                out = s.getOutputStream();
                in = new DataInputStream(s.getInputStream());
                byte[] encryptionVector = new byte[128];
                in.readFully(encryptionVector, 0, 128); // Read the encryption initialization vector
                int serverTime = in.readInt();          // Read the server time stamp

                /** local variable used for populating byte arrays. */
                String temp;

                /** Set up the NSCA host that the push is initiated from */
                byte[] hostName = new byte[64];
                temp = (null == reportingHost) ? "UNKNOWN" : reportingHost;
                System.arraycopy(temp.getBytes(), 0, hostName, 0, temp.getBytes().length);

                // Set up the reporting service name.
                byte[] serviceName = new byte[128];
                temp = (null == nscaService) ? "UNKNOWN" : nscaService;
                System.arraycopy(temp.getBytes(), 0, serviceName, 0, temp.getBytes().length);

                // Set up the free text message.
                byte[] pluginOutput = new byte[512];

                // NSCA doesn't handle line feeds very well, so remove them
                message.replaceAll("\n", "");
                if ((null != message) && (message.getBytes().length <= 512)) {
                    System.arraycopy(message.getBytes(), 0, pluginOutput, 0, message.getBytes().length);
                } else if (null != message) {
                    System.arraycopy(message.getBytes(), 0, pluginOutput, 0, pluginOutput.length);
                } else {
                    System.arraycopy("<null>".getBytes(), 0, pluginOutput, 0, pluginOutput.length);
                }

                // alert is made up of 4 ints, followed by 3 strings
                int alertSize = 4 + 4 + 4 + 4 + hostName.length + serviceName.length + pluginOutput.length;
                byte[] alert = new byte[alertSize];

                // 1st int
                alert[0] = (byte) ((nscaVersion >> 8) & 0xff);
                alert[1] = (byte) (nscaVersion & 0xff);

                // 2nd int; calculate the crc with zeroes in the crc field
                alert[4] = (byte) ((0 >> 24) & 0xff);
                alert[5] = (byte) ((0 >> 16) & 0xff);
                alert[6] = (byte) ((0 >> 8) & 0xff);
                alert[7] = (byte) (0 & 0xff);

                // 3rd int (echo the time read from the server)
                alert[8]  = (byte) ((serverTime >> 24) & 0xff);
                alert[9]  = (byte) ((serverTime >> 16) & 0xff);
                alert[10] = (byte) ((serverTime >> 8) & 0xff);
                alert[11] = (byte) (serverTime & 0xff);

                // 4th int (this is the code associated with the alert)
                alert[12] = (byte) ((msgLevel >> 8) & 0xff);
                alert[13] = (byte) (msgLevel & 0xff);
                int offset = 14;

                // 1st of 3 strings
                System.arraycopy(hostName, 0, alert, offset, hostName.length);
                offset += hostName.length;

                // 2nd of 3 strings
                System.arraycopy(serviceName, 0, alert, offset, serviceName.length);
                offset += serviceName.length;

                // 3rd of 3 strings
                System.arraycopy(pluginOutput, 0, alert, offset, pluginOutput.length);
                offset += pluginOutput.length;

                // now we can calculate the crc
                CRC32 crc = new CRC32();
                crc.update(alert);
                long crcValue = crc.getValue();

                // now that we've calculated the crc, fill it in
                alert[4] = (byte) ((crcValue >> 24) & 0xff);
                alert[5] = (byte) ((crcValue >> 16) & 0xff);
                alert[6] = (byte) ((crcValue >> 8) & 0xff);
                alert[7] = (byte)  (crcValue & 0xff);

                encryptBuffer(_encryptionMethod, alert, encryptionVector);

                logger.debug("Writing to socket; encryptionVector=" + encryptionVector + "; alert=" + alert.toString());
                out.write(alert, 0, alert.length);
                out.flush();

                logger.debug("Cleaning up");
                out.close(); out = null;
                in.close();  in  = null;
                s.close();   s   = null;
                finished = true;
                logger.debug("Finished");
            } catch (Exception e) {
                logger.debug(e.getMessage());
            } finally {
                if (null != out) {
                    try {
                        out.close();
                    } catch (Exception ee) {
                        logger.debug("Runnable Exception while closing OutputStream: '" + ee.getMessage());
                    }
                }
                if (null != out) {
                    try {
                        in.close();
                    } catch (Exception ee) {
                        logger.debug("Runnable Exception while closing InputStream: '" + ee.getMessage());
                    }
                }
                if (null != s) {
                    try {
                        s.close();
                    } catch (Exception ee) {
                        logger.debug("Runnable Exception while closing socket: '" + ee.getMessage());
                    }
                }
                synchronized(this) {
                    notifyAll();
                }
            }
        }
    }
}
