package hierarchy;

import java.io.IOException;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import authenticator.Authenticator;
import authenticator.protobuf.AuthWalletHierarchy.HierarchyAddressTypes;
import authenticator.protobuf.AuthWalletHierarchy.HierarchyCoinTypes;
import authenticator.protobuf.AuthWalletHierarchy.HierarchyPurpose;

import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.crypto.ChildNumber;
import com.google.bitcoin.crypto.DeterministicHierarchy;
import com.google.bitcoin.crypto.DeterministicKey;
import com.google.bitcoin.crypto.HDKeyDerivation;
import com.google.bitcoin.crypto.MnemonicCode;
import com.google.bitcoin.crypto.MnemonicException.MnemonicLengthException;

public class BAHierarchy{
	
	DeterministicHierarchy hierarchy;
	DeterministicKey rootKey;
	List<ChildNumber> accountsHeightsUsed;
	
	@SuppressWarnings("static-access")
	public BAHierarchy(byte[] seed, HierarchyCoinTypes coinType){
		HDKeyDerivation HDKey = null;
    	DeterministicKey masterkey = HDKey.createMasterPrivateKey(seed);
    	// purpose level
    	ChildNumber purposeIndex = new ChildNumber(HierarchyPurpose.Bip43_VALUE,true); // is harden
    	DeterministicKey purpose = HDKey.deriveChildKey(masterkey,purposeIndex);
    	// coin level
    	ChildNumber coinIndex = new ChildNumber(coinType.getNumber(),true); // is harden
    	DeterministicKey coin = HDKey.deriveChildKey(purpose,coinIndex);
    	
    	//put root
    	setRoot(coin);
	}
	public BAHierarchy setRoot(DeterministicKey rootKey) {
		this.rootKey = rootKey;
		hierarchy = new DeterministicHierarchy(rootKey);
		return this;
	}
		
	
	public void buildWalletHierarchyForStartup(List<Integer> accountByNumberOfKeys){
		accountsHeightsUsed = new ArrayList<ChildNumber>();
		for(int i=0;i < accountByNumberOfKeys.size(); i++){
			ChildNumber in = new ChildNumber(accountByNumberOfKeys.get(i),false); // not hardened
			accountsHeightsUsed.add(in);
		}
	}
	
	
	
	/**
	 * This function is designed to reconstruct async all the wallet's hierarchy on wallet's startup.<br>
	 * <b>Do not mistake with wallet reconstruction from seed.</b>
	 * @param accountByNumberOfKeys
	 */
	/*public void buildWalletHierarchyForStartup(List<Integer> accountByNumberOfKeys){
		for(int i=0;i < accountByNumberOfKeys.size(); i++)
			buildWalletHierarchy(i, HierarchyAddressTypes.Spending ,0,	accountByNumberOfKeys.get(i));
	}
	
	private void buildWalletHierarchy(int accountIndex,HierarchyAddressTypes addressTypeIndex,int from, int to){
		new Thread(new Runnable() {
			           public void run() {			
			        	   List<ChildNumber> p = new ArrayList<ChildNumber>(rootKey.getPath());
			        	   ChildNumber account = new ChildNumber(accountIndex,true);
			        	   p.add(account);
			        	   ChildNumber addressType = new ChildNumber(addressTypeIndex.getNumber(),false);
			        	   p.add(addressType);
			        	   //
			        	   for(int i=from;i <= to;i++){
			       			ChildNumber index = new ChildNumber(i,true); // accounts are hardened
			       			p.add(index);
			       			hierarchy.deriveChild(p, false, true, index);
			       		}		
			        	   
			        	   
			        	   
			        	Authenticator.fireOnFinishedDiscoveringWalletHierarchy();
			         }
		}).start();
		
	}*/
	
	public DeterministicKey getNextSpendingKey(int accountIndex){
		// root
		List<ChildNumber> p = new ArrayList<ChildNumber>(rootKey.getPath());
		// account
		ChildNumber account = new ChildNumber(accountIndex,true);
 	    p.add(account);
 	    // address type
 	    ChildNumber addressType = new ChildNumber(HierarchyAddressTypes.Spending_VALUE,false); // TODO - also savings addresses
 	    p.add(addressType);	    
	    
	    DeterministicKey ret = hierarchy.deriveChild(p, false, true, accountsHeightsUsed.get(accountIndex));	
	    
	    accountsHeightsUsed.set(accountIndex,new ChildNumber(accountsHeightsUsed.get(accountIndex).getI() + 1,false) ); 
	    
		return ret;
	}
	
	public DeterministicKey getKeyFromAcoount(int accountIndex, int addressKey){
		// root
		List<ChildNumber> p = new ArrayList<ChildNumber>(rootKey.getPath());
		// account
		ChildNumber account = new ChildNumber(accountIndex,true);
 	    p.add(account);
 	    // address type
 	    ChildNumber addressType = new ChildNumber(HierarchyAddressTypes.Spending_VALUE,false); // TODO - also savings addresses
 	    p.add(addressType);
 	    // address
 	    ChildNumber ind = new ChildNumber(addressKey,false);
	    accountsHeightsUsed.set(accountIndex,ind ); 
	    
	    DeterministicKey ret = hierarchy.deriveChild(p, false, true, ind);	    
		return ret;
	}
	
	public int generateNewAccount(){
		int ret = accountsHeightsUsed.size();
		accountsHeightsUsed.add(new ChildNumber(0,false));
		return ret;
	}
	
	/**
	 * This method implements BIP39 to generate a 512 bit seed from 128 bit checksummed entropy. The seed and the
	 * mnemonic encoded entropy are saved to internal storage.
	 */
	public static byte[] generateMnemonicSeed(){
		//Generate 128 bits entropy.
        SecureRandom secureRandom = null;
		try {
			secureRandom = SecureRandom.getInstance("SHA1PRNG");
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		byte[] bytes = new byte[16];
		secureRandom.nextBytes(bytes);
		MnemonicCode ms = null;
		try {
			ms = new MnemonicCode();
		} catch (IOException e) {
			e.printStackTrace();
		}
		List<String> mnemonic = null;
		try {
		mnemonic = ms.toMnemonic(bytes);
		} catch (MnemonicLengthException e) {
			e.printStackTrace();
		}
		byte[] seed = MnemonicCode.toSeed(mnemonic, "");	
		return seed;		
	}

}