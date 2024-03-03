package net.ircDDB.irc;
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

public class IRCClientStateMachine {
    private static final Logger LOGGER = LogManager.getLogger(IRCClientStateMachine.class);

    private final IRCClient client;
    private final IRCProtocol ircProtocol;
    private final IRCMessageQueue recvQ;
    private final IRCMessageQueue sendQ;
    private final SocketConnection socketConnection;
    /**
     * Amount of cycles to skip the action - one cycle is 0.5s
     */
    private int timer = 0;
    private State state = State.DISCONNECTED;
    private enum State {
        DISCONNECTED,
        DISCONNECTING,
        ACTIVE
    }

    public IRCClientStateMachine(IRCClient client, IRCProtocol ircProtocol, IRCMessageQueue recvQ,
                                 IRCMessageQueue sendQ, SocketConnection socketConnection) {
        this.client = client;
        this.ircProtocol = ircProtocol;
        this.recvQ = recvQ;
        this.sendQ = sendQ;
        this.socketConnection = socketConnection;
    }

    public void doAct() {
        if (timer == 0) {
            switch (state) {
                case DISCONNECTED:
                    handleDisconnected();
                    break;

                case ACTIVE:
                    handleActive();
                    break;

                case DISCONNECTING:
                    handleDisconnecting();
                    break;
            }
        } else {
            timer--;
        }

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore interrupt flag
            LOGGER.warn("sleep interrupted " + e);
        }
    }

    private void handleDisconnecting() {
        client.closeConnection();
        timer = 30; // wait 15 seconds
        state = State.DISCONNECTED;
    }

    private void handleActive() {
        if (recvQ.isEOF()) {
            timer = 0;
            state = State.DISCONNECTING;
        } else if (!ircProtocol.processQueues(recvQ, sendQ)) {
            timer = 0;
            state = State.DISCONNECTING;
        }

        while ((state == State.ACTIVE) && sendQ.messageAvailable()) {
            IRCMessage m = sendQ.getMessage();

            try {
                m.writeMessage(socketConnection.getOutputStream());
            } catch (IOException e) {
                LOGGER.error("IRCClient/write: ", e);
                timer = 0;
                state = State.DISCONNECTING;
            }
        }
    }

    private void handleDisconnected() {
        LOGGER.info("IRCClient: connect request");
        if (client.init()) {
            LOGGER.info("IRCClient: connected");
            state = State.ACTIVE;
            timer = 1;
        } else {
            timer = 1;
            state = State.DISCONNECTING;
        }
    }
}
