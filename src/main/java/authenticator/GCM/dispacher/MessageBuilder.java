package authenticator.GCM.dispacher;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;

import org.json.JSONException;
import org.json.JSONObject;


public class MessageBuilder extends JSONObject{
	public MessageBuilder(MessageType type,String ... arg) throws JSONException
	{
		JSONObject reqPayload;
		switch (type){
			case test:
				this.append("data","Hello World");
				break;
			/**
			 * arg - <br>
			 * 0 - PairingID<br>
			 * 1 - ExternalIP <br>
			 * 2 - LocalIP <br>
			 * 3 - CustomMsg <br>
			 */
			case signTx:
				this.put("tmp", new Timestamp( new java.util.Date().getTime() ));
				this.put("PairingID", arg[0]); 
				this.put("RequestType", type.getValue()); 
				reqPayload = new JSONObject();
				reqPayload.put("ExternalIP", arg[1]);
				reqPayload.put("LocalIP", arg[2]);
				this.put("ReqPayload", reqPayload);
				this.put("CustomMsg", arg[3]); // TODO localize
				this.put("RequestID", getRequestIDDigest(this));
				break;
			/**
			 * arg - <br>
			 * 0 - PairingID<br>
			 * 1 - ExternalIP <br>
			 * 2 - LocalIP <br>
			 * 3 - CustomMsg <br>
			 * 4 - RequestID
			 */
			case updateIpAddressesForPreviousMessage:
				this.put("tmp", new Timestamp( new java.util.Date().getTime() ));
				this.put("PairingID", arg[0]); 
				this.put("RequestType", type.getValue()); 
				reqPayload = new JSONObject();
				reqPayload.put("ExternalIP", arg[1]);
				reqPayload.put("LocalIP", arg[2]);
				this.put("ReqPayload", reqPayload);
				this.put("CustomMsg", arg[3]); // TODO localize
				this.put("RequestID", arg[4]);
				break;
		}
	}
	
	private String getConcatinatedPayload(MessageBuilder msg) throws JSONException{
		String ReqPayload = msg.get("ReqPayload").toString();
		String tmp = msg.get("tmp").toString();
		String RequestType = msg.get("RequestType").toString();
		String PairingID = msg.get("PairingID").toString();
		return ReqPayload +
				tmp + 
				RequestType + 
				PairingID;
	}
	
	private String getRequestIDDigest(MessageBuilder msg) throws JSONException
	 {
		MessageDigest md = null;
		try {md = MessageDigest.getInstance("SHA-1");}
		catch(NoSuchAlgorithmException e) {e.printStackTrace();} 
	    byte[] digest = md.digest(getConcatinatedPayload(msg).getBytes());
	    String ret = new BigInteger(1, digest).toString(16);
	    //Make sure it is 40 chars, if less pad with 0, if more substringit
	    if(ret.length() > 40)
	    {
	    	ret = ret.substring(0, 39);
	    }
	    else if(ret.length() < 40)
	    {
	    	int paddingNeeded = 40 - ret.length();
	    	String padding = "";
	    	for(int i=0;i<paddingNeeded;i++)
	    		padding = padding + "0";
	    	ret = padding + ret;
	    }
	    return ret;
	}
}