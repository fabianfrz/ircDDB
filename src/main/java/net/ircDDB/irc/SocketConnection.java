package net.ircDDB.irc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.Arrays;

public class SocketConnection {
    private static final Logger LOGGER = LogManager.getLogger(SocketConnection.class);
    private Socket socket;


    public boolean initSocket(String host, int port) {
        InetAddress[] adr;
        try {
            adr = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            LOGGER.error("IRCClient/unknown host", e);
            return true;
        }
        if (adr != null) {
            int num = adr.length;

            if (num > 0 && num < 15) {
                LOGGER.info("IRCClient/found " + num + " addresses:");
                Arrays.stream(adr).forEach((a) -> LOGGER.info("  " + a.getHostAddress()));

                int[] shuffle = Utils.getShuffledAddresses(num);

                socket = null;
                for (int i = 0; i < num; i++) {
                    InetAddress a = adr[shuffle[i]];

                    LOGGER.info("IRCClient/trying: " + a.getHostAddress());
                    if (a instanceof Inet4Address || a instanceof Inet6Address) {

                        try {
                            socket = new Socket();
                            InetSocketAddress endp = new InetSocketAddress(a, port);
                            socket.connect(endp, 5000);  // 5 seconds timeout
                        } catch (IOException e) {
                            LOGGER.warn("IRCClient/ioexception: ", e);
                            socket = null;
                        }

                    }
                    if (socket != null) {
                        break;
                    }
                }

            } else {
                LOGGER.error("IRCClient/invalid number of addresses: " + adr.length);
                return true;
            }
        }

        if (socket == null) {
            LOGGER.error("IRCClient: no connection");
            return true;
        }
        return false;
    }

    public InputStream getInputStream() throws IOException {
        return socket.getInputStream();
    }

    public OutputStream getOutputStream() throws IOException {
        return socket.getOutputStream();
    }

    public void close() {

        if (socket != null) {
            try {
                socket.shutdownInput();
            } catch (IOException e) {
                LOGGER.warn("IRCClient/socket.shutdownInput: ", e);
            }

            try {
                socket.close();
            } catch (IOException e) {
                LOGGER.warn("IRCClient/socket.close: ", e);
            }

        }
    }
}
