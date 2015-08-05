package com.couchbase.blip;

public interface MessageDelegate
{
	void onCompleted(Message msg);
}
