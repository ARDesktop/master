package org.poc.vnc.domain;

/**
 * Created by joel on 4/19/15.
 */
public interface IConnection {
    String getUserName();
    String getPort();
    String getPassword();
    String getAddress();
}
