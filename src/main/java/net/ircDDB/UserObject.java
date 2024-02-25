package net.ircDDB;

public class UserObject {
    private String nick;
    private String name;
    private String host;
    private boolean op;

    UserObject(String nick, String name, String host) {
        this.nick = nick;
        this.name = name;
        this.host = host;
        op = false;
    }

    public String getNick() {
        return nick;
    }

    public void setNick(String nick) {
        this.nick = nick;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public boolean isOp() {
        return op;
    }

    public void setOp(boolean op) {
        this.op = op;
    }
}
