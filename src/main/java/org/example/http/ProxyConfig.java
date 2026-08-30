package org.example.http;

/**
 * Proxy config in format: host:port:username:password
 */
public record ProxyConfig(String host, int port, String username, String password, boolean secure) {
    public ProxyConfig(String host, int port, String username, String password) {
        this(host, port, username, password, true);
    }

    public boolean hasAuth() {
        return username != null && !username.isBlank() && password != null;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProxyConfig that = (ProxyConfig) o;
        return port == that.port && 
               secure == that.secure &&
               host.equals(that.host) &&
               java.util.Objects.equals(username, that.username) &&
               java.util.Objects.equals(password, that.password);
    }
    
    @Override
    public int hashCode() {
        return java.util.Objects.hash(host, port, username, password, secure);
    }
}


