/*

ircDDB

Copyright (C) 2010   Michael Dirska, DL1BFF (dl1bff@mdx.de)

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

*/


package net.ircDDB;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.io.EOFException;
import java.io.IOException;


public class IRCReceiver implements Runnable {
    private static final Logger LOGGER = LogManager.getLogger(IRCReceiver.class);

    private final InputStream is;
    private final IRCMessageQueue q;

    IRCReceiver(InputStream inputStream, IRCMessageQueue messageQueue) {
        is = inputStream;
        q = messageQueue;
    }


    public void run() {
        IRCMessage m = new IRCMessage();

        int state = 0;

        byte[] bb = new byte[1000];

        while (true) {
            byte b;
            int res;

            try {
                res = is.read(bb);

                if (res <= 0) {
                    LOGGER.info("IRCClient/readByte EOF2");
                    q.signalEOF();
                    return;
                }

            } catch (EOFException e) {
                LOGGER.info("IRCClient/readByte EOF");
                q.signalEOF();
                return;
            } catch (IOException e) {
                LOGGER.warn("IRCClient/readByte ", e);
                q.signalEOF();
                return;
            }

            int i;

            for (i = 0; i < res; i++) {
                b = bb[i];


                if (b > 0) {

                    if (b == 10) {
                        q.putMessage(m);
                        m = new IRCMessage();
                        state = 0;
                    } else if (b == 13) {
                        // do nothing
                    } else switch (state) {
                        case 0:
                            if (b == ':') {
                                state = 1; // prefix
                            } else if (b == 32) {
                                // do nothing
                            } else {
                                m.command = Character.toString((char) b);
                                state = 2; // command
                            }
                            break;

                        case 1:
                            if (b == 32) {
                                state = 2; // command is next
                            } else {
                                m.prefix = m.prefix + (char) b;
                            }
                            break;

                        case 2:
                            if (b == 32) {
                                state = 3; // params
                                m.numParams = 1;
                                m.params[m.numParams - 1] = "";
                            } else {
                                m.command = m.command + (char) b;
                            }
                            break;

                        case 3:
                            if (b == 32) {
                                m.numParams++;
                                if (m.numParams >= m.params.length) {
                                    state = 5; // ignore the rest
                                }

                                m.params[m.numParams - 1] = "";
                            } else if ((b == ':') && (m.params[m.numParams - 1].isEmpty())) {
                                state = 4; // rest of line is this param
                            } else {
                                m.params[m.numParams - 1] = m.params[m.numParams - 1] + (char) b;
                            }
                            break;

                        case 4:
                            m.params[m.numParams - 1] = m.params[m.numParams - 1] + (char) b;
                            break;
                    }

                }

            }
        }


    }
}
