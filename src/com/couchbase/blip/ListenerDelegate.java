package com.couchbase.blip;

public interface ListenerDelegate
{
	public void connectionOpened(Connection connection);
	public void connectionClosed(Connection connection);
}