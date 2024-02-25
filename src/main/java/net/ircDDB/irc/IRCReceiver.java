/*

ircDDB

Copyright (C) 2010   Michael Dirska, DL1BFF (dl1bff@mdx.de)
Copyright (C) 2024   Fabian Franz BSc., OE9LTX

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


package net.ircDDB.irc;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * This class represents a messege in IRC.
 * Probably better to replace it by a IRC library.
 */
public class IRCReceiver implements Runnable {
    private static final Logger LOGGER = LogManager.getLogger(IRCReceiver.class);

    private final InputStream is;
    private final IRCMessageQueue q;
    private int state = 0;

    IRCReceiver(InputStream inputStream, IRCMessageQueue messageQueue) {
        is = inputStream;
        q = messageQueue;
    }


    public void run() {
        IRCMessage m = new IRCMessage();

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

            for (int i = 0; i < res; i++) {
                b = bb[i];
                if (b > 0) {
                    if (b == 10) {
                        q.putMessage(m);
                        m = new IRCMessage();
                        state = 0;
                    } else if (b != 13) {
                        doAction(b, m);
                    }
                }
            }
        }
    }

    private void doAction(byte b, IRCMessage m) {
        switch (state) {
            case 0:
                performState0(b, m);
                break;

            case 1:
                performState1(b, m);
                break;

            case 2:
                performState2(b, m);
                break;

            case 3:
                performState3(b, m);
                break;

            case 4:
                performState4((char) b, m);
                break;
        }
    }

    private static void performState4(char b, IRCMessage m) {
        m.params[m.numParams - 1] = m.params[m.numParams - 1] + b;
    }

    private void performState3(byte b, IRCMessage m) {
        if (b == 32) {
            m.numParams++;
            if (m.numParams >= m.params.length) {
                state = 5; // ignore the rest
            }

            m.params[m.numParams - 1] = "";
        } else if ((b == ':') && (m.params[m.numParams - 1].isEmpty())) {
            state = 4; // rest of line is this param
        } else {
            performState4((char) b, m);
        }
    }

    private void performState2(byte b, IRCMessage m) {
        if (b == 32) {
            state = 3; // params
            m.numParams = 1;
            m.params[m.numParams - 1] = "";
        } else {
            m.command = m.command + (char) b;
        }
    }

    private void performState1(byte b, IRCMessage m) {
        if (b == 32) {
            state = 2; // command is next
        } else {
            m.prefix = m.prefix + (char) b;
        }
    }

    private void performState0(byte b, IRCMessage m) {
        if (b == ':') {
            state = 1; // prefix
        } else if (b != 32) {
            m.command = Character.toString((char) b);
            state = 2; // command
        }
    }
}
