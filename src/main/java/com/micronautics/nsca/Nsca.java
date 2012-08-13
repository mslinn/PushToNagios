/*
 * Copyright 1999,2004 The Apache Software Foundation.
 * Portions copyright 2012 Bookish, LLC.
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

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * Java native equivalent to nsca_send program. It can encrypt and send alerts to the NSCA server. Example usage:
 * <pre>try {
 * 	   Nsca nsca = new Nsca();
 * 	   nsca.setConfigFile("nsca_send_clear.properties");
 * 	   nsca.send(NAGIOS_WARN, "Your pants are on fire!");
 * } catch (Exception e) {
 * 	   System.out.println(e.getMessage());
 * }</pre>
 * @see <a href="http://mikeslinn.blogspot.com/2012/08/pushing-notifications-to-nagios-from.html">Pushing Notifications to Nagios from Java and Scala</a>
 * @author <a href="mailto:jarlyons@gmail.com">Jar Lyons</a> Original Java version, packaged with log4j/MDC
 * @author <a href="mailto:mslinn@micronauticsresearch.com">Mike Slinn</a> standalone version, packaged with SBT and converted to use HOCON */
public class Nsca {
    private static final int INITIALIZATION_VECTOR_SIZE = 128;

    public enum Encryption {
        NONE(0), XOR(1);

        private int value;

        private Encryption(int v) { value = v; }

        public static Encryption parse(int v) {
            for (Encryption value : values())
                if (value.value==v)
                    return value;
            logger.warn("Invalid encryption value: '" + v + "'; using NONE (0)");
            return NONE;
        }
    }

    public enum NagiosMsgLevel {
        NO_MSG(-1), OK(0), WARN(1), CRITICAL(2), UNKNOWN(3);

        private int value;

        private NagiosMsgLevel(int v) { value = v; }

        public static NagiosMsgLevel parse(int v) {
            for (NagiosMsgLevel value : values())
                if (value.value==v)
                    return value;
            logger.warn("Invalid Nagios message level: '" + v + "'; using OK (0)");
            return OK;
        }
    }

    /** Shared amongst all instances */
    protected static ThreadPoolExecutor threadPool;

    /** Shared amongst all instances */
    protected static LinkedBlockingQueue queue;

    private static Logger logger = LoggerFactory.getLogger(Nsca.class);

    private Encryption _encryptionMethod = Encryption.NONE;

    /** NSCA password (optional and only used with XOR) */
    private String _password = "";

    private int nscaVersion = 3;

    private int poolSize = 50;
    private int maxPoolSize = 100;
    private long keepAliveTime = 10;

    /** Optional message to be sent to Nagios when the appender is instantiated. */
    private String startupMsg = "";

    /** Level of optional message to be sent to Nagios when the appender is instantiated. */
    private NagiosMsgLevel startupMsgLevel = NagiosMsgLevel.NO_MSG;

    /** Message delivery timeout */
    private int timeout = 5000;

    /** Nagios host where the nsca server is running */
    private String nscaHost = "localhost";

    /** Nagios port where the nsca server is running */
    private int nscaPort = 5667;

    /** host name running this code */
    private final String reportingHost = getHost();

    /** Nagios service name to associate with the messages forwarded to NSCA server */
    private String nscaService = "UNSPECIFIED_SERVICE";


    public Nsca() throws Exception {
        configure("nsca {}", null);
        maybeCreateThreadPool();
    }

    /**
     * @param strConf might contain config info <code>nsca</code> section in HOCON format; can be null. Example:<pre>
     * nsca { encryption_method = 0 \n nscaService = blah }
     * </pre>
     * If no config information is found, the defaults are: no encryption, Nagios on localhost:5667, will send to "UNSPECIFIED_SERVICE".
     * @see <a href="http://typesafehub.github.com/config/latest/api/index.html?com/typesafe/config/ConfigParseOptions.html">Config Parse Options</a>
     * @see <a href="https://github.com/typesafehub/config">Config project on GitHub</a>
     * @throws Exception
     */
    public Nsca(String strConf) throws Exception {
        configure(strConf, null);
        maybeCreateThreadPool();
    }

    /**
     * @param caller used to define substitutions of HOCON values read from config files
     * @throws Exception
     */
    public Nsca(Class caller) throws Exception {
        configure("nsca {}", caller);
        maybeCreateThreadPool();
    }

    /**
     * @param strConf might contain config info <code>nsca</code> section in HOCON format; can be null. Example:<pre>
     * nsca { encryption_method = 0 \n nscaService = blah }
     * </pre>
     * If no config information is found, the defaults are: no encryption, Nagios on localhost:5667, will send to "UNSPECIFIED_SERVICE".
     * @param caller used to define substitutions of HOCON values.
     * @see <a href="http://typesafehub.github.com/config/latest/api/index.html?com/typesafe/config/ConfigParseOptions.html">Config Parse Options</a>
     * @see <a href="https://github.com/typesafehub/config">Config project on GitHub</a>
     * @throws Exception
     */
    public Nsca(String strConf, Class caller) throws Exception {
        configure(strConf, caller);
        maybeCreateThreadPool();
    }

    /** Set the configuration parameters instead of reading them from the config file; encryption method defaults to none,
     * will send to "UNSPECIFIED_SERVICE". */
    public Nsca(String host, int port, String service) throws Exception {
        init(host, port, service, Encryption.NONE, "");
    }

    /** Set the configuration parameters instead of reading them from the config file.
     * @param encryptionMethod The new encryption method (0=None, 1=XOR)
     * @param password must be specified for encryption methods other than None. */
    public Nsca(String host, int port, String service, Encryption encryptionMethod, String password) throws Exception {
        init(host, port, service, encryptionMethod, password);
    }

    public Encryption getEncryptionMethod() { return _encryptionMethod; }

    public String getNscaHost() { return nscaHost; }

    public int getNscaPort() { return nscaPort; }

    public String getNscaService() { return nscaService; }

    public String getReportingHost() { return reportingHost; }

    /** Push the alert to the nagios server. If the server is not present a warning is logged but no exception is raised.
     * @param msgLevel one of NAGIOS_UNKNOWN, NAGIOS_OK, NAGIOS_WARN, or NAGIOS_CRITICAl
     * @param message up to 256 characters long */
    public void send(NagiosMsgLevel msgLevel, String message) throws Exception {
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
        startupMsgLevel = NagiosMsgLevel.NO_MSG;
        startupMsg = null;
    }

    public void setStartupMessage(NagiosMsgLevel msgLevel, String msgText) {
        startupMsgLevel = msgLevel;
        startupMsg = msgText;
    }

    /** Encrypts the send buffer according the nsca encryption method
     * @param buffer Buffer to be encrypted
     * @param encryptionVector Encryption Initialization Vector
     * @throws Exception for unsupported encryption scheme */
    public void encryptBuffer(Encryption encryptionMethod, byte[] buffer, byte[] encryptionVector) throws Exception {
        switch (encryptionMethod) {
            case NONE:
                break;

            case XOR:
                /* rotate over encryptionVector received from the server */
                for (int y = 0, x = 0; y < buffer.length; y++, x++) {
                    /* keep rotating over encryptionVector */
                    if (x >= INITIALIZATION_VECTOR_SIZE)
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

    public static String getFileContents(String filename) {
        InputStream resStream = Nsca.class.getClassLoader().getResourceAsStream(filename);
        if (resStream==null)
            logger.warn("Could not load '%s' from the classpath".format(filename));
        try { // see http://weblogs.java.net/blog/pat/archive/2004/10/stupid_scanner_1.html
            return new java.util.Scanner(resStream).useDelimiter("\\A").next();
        } catch (java.util.NoSuchElementException e) {
            return "";
        }
    }

    protected static String substitute(String input, String packageName, String className) {
        return input.replaceAll("%className%", className).replaceAll("%packageName%", packageName);
    }

    /**
     * @param strConf might contain config info; can be null.
     * @see #Nsca(String)
     * @return Boolean specifying whether configuration succeeded
     */
    protected void configure(String strConf, Class caller) throws Exception {
        String className = "";
        String packageName = "";
        if (caller!=null) {
            String fqName = caller.getName();
            className = fqName.substring(fqName.lastIndexOf(".")+1);
            packageName = fqName.substring(0, fqName.lastIndexOf("."));
        }

        Config configApplication = ConfigFactory.empty();
        try {
            String contents = substitute(getFileContents("application.conf"), packageName, className);
            configApplication = ConfigFactory.parseString(contents);
        } catch (Exception e) {
            logger.warn("Warning: " + e.getMessage() + " reading application.conf; values will be taken from nsca.conf if present");
        }

        Config configNsca = ConfigFactory.empty();
        try {
            String contents = substitute(getFileContents("nsca.conf"), packageName, className);
            configNsca = ConfigFactory.parseString(contents);
        } catch (Exception e) {
            logger.warn("Warning: " + e.getMessage() + " reading nsca.conf; default values will be used");
        }

        Config configStr = ConfigFactory.empty();
        if (strConf!=null)
            configStr = ConfigFactory.parseString(substitute(strConf, packageName, className));
        Config config = ConfigFactory.load(configStr).withFallback(configApplication).withFallback(configNsca).getConfig("nsca");

        try {
            _encryptionMethod = Encryption.parse(config.getInt("encryption_method"));
        } catch (Exception e) {
            logger.warn("encryption_method not found in config files, NONE (0) assumed");
        }

        try {
            _password  = config.getString("password");
        } catch (Exception e) {
            if (_encryptionMethod != Encryption.NONE)
               logger.warn("password not found in config files, empty string assumed");
        }

        try {
            nscaHost = config.getString("nscaHost");
        } catch (Exception e) {
            logger.warn("nscaHost not found in config files, " + nscaHost + " assumed");
        }

        try {
            nscaPort = config.getInt("nscaPort");
        } catch (Exception e) {
            logger.warn("nscaPort not found in config files, " + nscaPort + " assumed");
        }

        try {
            nscaService = config.getString("nscaService");
        } catch (Exception e) {
            logger.warn("nscaService not found in config files, '" + nscaService + "' assumed");
        }
    }

    private void maybeCreateThreadPool() {
        if (threadPool==null) {
            queue = new LinkedBlockingQueue(2000);
            threadPool = new ThreadPoolExecutor(poolSize, maxPoolSize, keepAliveTime, TimeUnit.SECONDS, queue);
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

    private void init(String host, int port, String service, Encryption encryptionMethod, String password) throws Exception {
        if (host==null || host.trim().length()==0)
            throw new Exception("No NSCA host specified");

        if (service==null || service.trim().length()==0)
            throw new Exception("No NSCA service specified");

        this.nscaHost = host;
        this.nscaPort = port;
        this.nscaService = service;

        if (encryptionMethod!=Encryption.NONE)
            _encryptionMethod = encryptionMethod;

        if (password != null) {
            password = password.trim();
            if (password.length() > 0)
                _password = password;
        }

        maybeCreateThreadPool();
    }

    private class NscaSendRunnable implements Runnable {
        private boolean finished = false;
        private String message;
        private NagiosMsgLevel msgLevel;
        private ArrayList buffer;

        public NscaSendRunnable(NagiosMsgLevel msgLevel, String message) {
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
                            logger.warn("Runnable Exception while closing socket: '" + e.getMessage());
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
                alert[12] = (byte) ((msgLevel.value >> 8) & 0xff);
                alert[13] = (byte) (msgLevel.value & 0xff);
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
                logger.warn("Nsca error sending to '" + nscaService + "' service monitor on " + nscaHost + ":" + nscaPort + " - " + e.getMessage());
            } finally {
                if (null != out) {
                    try {
                        out.close();
                    } catch (Exception ee) {
                        logger.warn("Runnable Exception while closing OutputStream: '" + ee.getMessage());
                    }
                }
                if (null != out) {
                    try {
                        in.close();
                    } catch (Exception ee) {
                        logger.warn("Runnable Exception while closing InputStream: '" + ee.getMessage());
                    }
                }
                if (null != s) {
                    try {
                        s.close();
                    } catch (Exception ee) {
                        logger.warn("Runnable Exception while closing socket: '" + ee.getMessage());
                    }
                }
                synchronized(this) {
                    notifyAll();
                }
            }
        }
    }
}
