package com.couchbase.blip;

public interface ReplyListener
{
	/**
	 * Called when the reply proxy is completed
	 * @param reply the completed reply
	 */
	void onCompleted(Message reply);
}
