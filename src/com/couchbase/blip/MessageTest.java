package com.couchbase.blip;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import javax.xml.bind.DatatypeConverter;

public class MessageTest
{
	@Test
	public void testDecode()
	{
		Message m = new Message();
		m.decode(new byte[] {18, 'K', 'e', 'y', 0x00, 'V', 'a', 'l', 'u', 'e', 0x00, 0x01, 0x00, 'H', 'e', 'l', 'l', 'o', 0x00, '!'});
		assertEquals(m.getProperty("Key"), "Value");
		assertNotEquals(m.getProperty("Key"), "Another value");
		assertNull(m.getProperty("Another key"));
		assertEquals(m.getProperty("Profile"), "Hello");
		assertEquals(m.body.length, 1);
		assertEquals(m.body[0], (byte)'!');
	}
	
	@Test
	public void testEncode()
	{
		Message m = new Message();
		m.isMine = true;
		m.isMutable = true;
		m.setProperty("Key", "Value");
		m.setProperty("Profile", "test profile");
		m.body = new byte[] {(byte)0xca, (byte)0xfe, (byte)0xba, (byte)0xbe};
		byte[] encoding = m.encode();
		Message m2 = new Message();
		m2.decode(encoding);
		assertEquals(m2.getProperty("Key"), "Value");
		assertNull(m2.getProperty("Another key"));
		assertEquals(m2.getProperty("Profile"), "test profile");
		assertTrue(Arrays.equals(m2.body, new byte[] {(byte)0xca, (byte)0xfe, (byte)0xba, (byte)0xbe}));
	}
	
	@Test
	public void testCompression()
	{
		Message m = new Message();
		
	}
}
