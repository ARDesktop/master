package org.poc.vnc.domain;

import org.poc.vnc.domain.IConnection;

import java.io.Serializable;

/**
 * Created by joel on 4/19/15.
 */
public class Connection implements IConnection, Serializable {
    private final String m_UserName;
    private final String m_Port;
    private final String m_Password;
    private final String m_Address;

    public Connection(String userName, String port, String password, String address) {
        this.m_UserName = userName;
        this.m_Port = port;
        this.m_Password = password;
        this.m_Address = address;
    }

    @Override
    public String getUserName() {
        return m_UserName;
    }

    @Override
    public String getPort() {
        return m_Port;
    }

    @Override
    public String getPassword() {
        return m_Password;
    }

    @Override
    public String getAddress() {
        return m_Address;
    }

    private final static String FIELD__ADDRESS = "";
    private final static String FIELD__USERNAME = "";
    private final static String FIELD__PASSWORD = "";
    private final static String FIELD__PORT = "";

    /*public android.content.ContentValues Gen_getValues()
    {
        android.content.ContentValues values=new android.content.ContentValues();
        values.put(FIELD__ADDRESS,Long.toString(this.gen__Id));
        values.put(FIELD__USERNAME,this.gen_nickname);
        values.put(FIELD__PASSWORD,this.gen_address);
        values.put(FIELD__PORT,Integer.toString(this.gen_port));
    }*/
}
