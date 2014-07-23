package authenticator;

import authenticator.AuthenticatorGeneralEventsListener.HowBalanceChanged;
import authenticator.BAApplicationParameters.NetworkType;
import authenticator.helpers.exceptions.AddressNotWatchedByWalletException;
import authenticator.helpers.exceptions.AddressWasNotFoundException;
import authenticator.hierarchy.BAHierarchy;
import authenticator.hierarchy.HierarchyUtils;
import authenticator.hierarchy.exceptions.IncorrectPathException;
import authenticator.hierarchy.exceptions.KeyIndexOutOfRangeException;
import authenticator.hierarchy.exceptions.NoAccountCouldBeFoundException;
import authenticator.hierarchy.exceptions.NoUnusedKeyException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.image.Image;

import javax.annotation.Nullable;

import org.json.JSONException;
import org.slf4j.Logger;

import wallettemplate.Main;
import authenticator.Utils.BAUtils;
import authenticator.db.ConfigFile;
import authenticator.protobuf.AuthWalletHierarchy.HierarchyAddressTypes;
import authenticator.protobuf.AuthWalletHierarchy.HierarchyCoinTypes;
import authenticator.protobuf.ProtoConfig.ATAddress;
import authenticator.protobuf.ProtoConfig.AuthenticatorConfiguration;
import authenticator.protobuf.ProtoConfig.AuthenticatorConfiguration.ATAccount;
import authenticator.protobuf.ProtoConfig.PairedAuthenticator;
import authenticator.protobuf.ProtoConfig.PendingRequest;
import authenticator.protobuf.ProtoConfig.WalletAccountType;

import com.google.bitcoin.core.AbstractWalletEventListener;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.Coin;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.InsufficientMoneyException;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.PeerGroup;
import com.google.bitcoin.core.ScriptException;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionConfidence;
import com.google.bitcoin.core.TransactionConfidence.ConfidenceType;
import com.google.bitcoin.core.TransactionInput;
import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.WalletEventListener;
import com.google.bitcoin.core.Wallet.ExceededMaxTransactionSize;
import com.google.bitcoin.core.Wallet.SendResult;
import com.google.bitcoin.crypto.DeterministicKey;
import com.google.bitcoin.crypto.HDKeyDerivation;
import com.google.bitcoin.crypto.TransactionSignature;
import com.google.bitcoin.script.Script;
import com.google.bitcoin.script.ScriptBuilder;
import com.google.bitcoin.wallet.CoinSelection;
import com.google.bitcoin.wallet.DefaultCoinSelector;
import com.google.bitcoin.wallet.DeterministicSeed;
import com.google.common.collect.ImmutableList;


/**
 *<p>A super class for handling all wallet operations<br>
 * This class covers DB data retrieving, bitcoinj wallet operations, Authenticator wallet operations<br></p>
 * 
 * <b>Main components are:</b>
 * <ol>
 * <li>{@link authenticator.WalletWrapper} for normal bitcoinj wallet operations</li>
 * <li>Authenticator wallet operations</li>
 * <li>Pending requests control</li>
 * <li>Active account control</li>
 * </ol>
 * @author Alon
 */
public class WalletOperation extends BASE{
	
	private static WalletWrapper mWalletWrapper;
	private static BAHierarchy authenticatorWalletHierarchy;
	public static ConfigFile configFile;
	private static Logger staticLogger;
	private BAApplicationParameters AppParams;
	
	public WalletOperation(){ 
		super(WalletOperation.class);
	}
	
	/**
	 * Instantiate WalletOperations without bitcoinj wallet.
	 * 
	 * @param params
	 * @throws IOException
	 */
	public WalletOperation(BAApplicationParameters params, DeterministicSeed seed) throws IOException{
		super(WalletOperation.class);
		init(params, seed);
	}
	
	/**
	 * Instantiate WalletOperations with bitcoinj wallet
	 * 
	 * @param wallet
	 * @param peerGroup
	 * @throws IOException
	 */
	public WalletOperation(Wallet wallet, PeerGroup peerGroup, BAApplicationParameters params, DeterministicSeed seed) throws IOException{
		super(WalletOperation.class);
		if(mWalletWrapper == null){
			mWalletWrapper = new WalletWrapper(wallet,peerGroup);
			mWalletWrapper.addEventListener(new WalletListener());
		}
		
		init(params, seed);
	}
	
	public void dispose(){
		mWalletWrapper = null;
		authenticatorWalletHierarchy = null;
		configFile = null;
		staticLogger = null;
	}
	
	private void init(BAApplicationParameters params, DeterministicSeed seed) throws IOException{
		staticLogger = this.LOG;
		AppParams = params;
		if(configFile == null){
			configFile = new ConfigFile(params.getAppName());
			/**
			 * Check to see if a config file exists, if not, initialize
			 */
			if(!configFile.checkConfigFile()){
				//byte[] seed = BAHierarchy.generateMnemonicSeed();
				configFile.initConfigFile(seed.getSecretBytes());
			}
		}
		if(authenticatorWalletHierarchy == null)
		{
			//byte[] seed = configFile.getHierarchySeed();
			authenticatorWalletHierarchy = new BAHierarchy(seed.getSecretBytes(),HierarchyCoinTypes.CoinBitcoin);
			/**
			 * Load num of keys generated in every account to get 
			 * the next fresh key
			 */
			List<BAHierarchy.AccountTracker> accountTrackers = new ArrayList<BAHierarchy.AccountTracker>();
			List<ATAccount> allAccount = getAllAccounts();
			for(ATAccount acc:allAccount){
				BAHierarchy.AccountTracker at =   new BAHierarchy().new AccountTracker(acc.getIndex(), 
						acc.getUsedExternalKeysList(), 
						acc.getUsedInternalKeysList());
				
				accountTrackers.add(at);
			}
			
			authenticatorWalletHierarchy.buildWalletHierarchyForStartup(accountTrackers, getHierarchyNextAvailableAccountID());
		}
	}
	
	public BAApplicationParameters getApplicationParams(){
		return AppParams;
	}
	
	/**
	 *A basic listener to keep track of balances and transaction state.<br>
	 *Will mark addresses as "used" when any amount of bitcoins were transfered to the address.
	 * 
	 * @author alon
	 *
	 */
	public class WalletListener extends AbstractWalletEventListener {
		/**
		 * just keep track we don't add the same Tx several times
		 */
        List<String> confirmedTx;
        
        public WalletListener(){
        	confirmedTx = new ArrayList<String>();
        }
		
        @Override
        public void onCoinsSent(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
        	try {
				updateBalace(tx, true);
			} catch (Exception e) { e.printStackTrace(); }
        }
        
        public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
        	try {
				updateBalace(tx, true);
			} catch (Exception e) { e.printStackTrace(); }
        }
        
        public void onTransactionConfidenceChanged(Wallet wallet, Transaction tx) {
        	try {
				updateBalace(tx, false);
			} catch (Exception e) { e.printStackTrace(); }
        }
        
        private void updateBalace(Transaction tx, boolean isNewTx) throws Exception{
        	/**
        	 * 
        	 * Check for coins entering
        	 */
        	List<TransactionOutput> outs = tx.getOutputs();
        	// accountsEffectedPending is for collecting all effected account in pending confidence.
        	List<Integer> accountsEffectedPending = new ArrayList<Integer>();
        	// accountsEffectedBuilding is for collecting all effected account in pending confidence. 
        	List<Integer> accountsEffectedBuilding = new ArrayList<Integer>();
        	boolean didFireOnReceivedCoins = false;
        	for (TransactionOutput out : outs){
    			Script scr = out.getScriptPubKey();
    			String addrStr = scr.getToAddress(getNetworkParams()).toString();
    			if(Authenticator.getWalletOperation().isWatchingAddress(addrStr)){
    				ATAddress add = Authenticator.getWalletOperation().findAddressInAccounts(addrStr);
    				TransactionConfidence conf = tx.getConfidence();
    				switch(conf.getConfidenceType()){
    				case BUILDING:
    					/**
    					 * CONDITIONING:
    					 * If the transaction is new but we don't know about it, just add it to confirmed.
    					 * If the transaction is moving from pending to confirmed, make it so.
    					 */
    					if(!isNewTx && Authenticator.getWalletOperation().isPendingInTx(add.getAccountIndex(), tx.getHashAsString())){ 
    						if(!accountsEffectedBuilding.contains(add.getAccountIndex())) accountsEffectedBuilding.add(add.getAccountIndex());
    						moveFundsFromUnconfirmedToConfirmed(add.getAccountIndex(), out.getValue());
    						confirmedTx.add(tx.getHashAsString());
    						if(!didFireOnReceivedCoins){
    							Authenticator.fireOnBalanceChanged(tx, HowBalanceChanged.ReceivedCoins, ConfidenceType.BUILDING);
    							didFireOnReceivedCoins = true;
    						}
    					}
    					/**
    					 * IMPORTANT:
    					 * Doesn't add the tx to confirmedTx because we didn't know about it before so
    					 * we want to iterate all the outputs
    					 */
    					else if(isNewTx && !confirmedTx.contains(tx.getHashAsString())){
    						addToConfirmedBalance(add.getAccountIndex(), out.getValue());
    						if(!didFireOnReceivedCoins){
	    						Authenticator.fireOnBalanceChanged(tx, HowBalanceChanged.ReceivedCoins, ConfidenceType.BUILDING);
	    						didFireOnReceivedCoins = true;
    						}
    						markAddressAsUsed(add.getAccountIndex(),add.getKeyIndex(), add.getType());
    					}
    					break;
    				case PENDING:
    					if(!isNewTx)
    						; // do nothing
    					else if(!Authenticator.getWalletOperation().isPendingInTx(add.getAccountIndex(), tx.getHashAsString())){
    						if(!accountsEffectedPending.contains(add.getAccountIndex())) accountsEffectedPending.add(add.getAccountIndex());
    						addToUnConfirmedBalance(add.getAccountIndex(), out.getValue());
    						if(!didFireOnReceivedCoins){
	    						Authenticator.fireOnBalanceChanged(tx, HowBalanceChanged.ReceivedCoins, ConfidenceType.PENDING);
	    						didFireOnReceivedCoins = true;
    						}
    						
    						markAddressAsUsed(add.getAccountIndex(),add.getKeyIndex(), add.getType());
    					}
    					break;
    				case DEAD:
    					// how the fuck do i know from where i should subtract ?!?!
    					break;
    				}
    			}
        	}
        	
        	// In case there are several outputs from the same account (PENDING)
        	if(accountsEffectedPending.size() > 0)
        		for(Integer acIndx:accountsEffectedPending)
        			addPendingInTx(acIndx, tx.getHashAsString());      
        	
        	// In case there are several outputs from the same account (BUILDING)
        	if(accountsEffectedBuilding.size() > 0)
        		for(Integer acIndx:accountsEffectedBuilding)
        			removePendingInTx(acIndx, tx.getHashAsString());
        	
        	/**
        	 * Check for coins spending
        	 */
    		List<TransactionInput> ins = tx.getInputs();
    		accountsEffectedPending = new ArrayList<Integer>();
    		boolean didFireOnSendCoins = false;
        	for (TransactionInput in : ins){
        		TransactionOutput out = in.getConnectedOutput();
        		if(out != null) // could be not connected
    			{
        			Script scr = out.getScriptPubKey();
    				String addrStr = scr.getToAddress(getNetworkParams()).toString();
        			if(Authenticator.getWalletOperation().isWatchingAddress(addrStr)){
        				ATAddress add = Authenticator.getWalletOperation().findAddressInAccounts(addrStr);
        				TransactionConfidence conf = tx.getConfidence();
        				switch(conf.getConfidenceType()){
        				case BUILDING:
        					/**
        					 * IMPORTANT:
        					 * We can only get here for Tx we know because we sent them.
        					 */
        					if(!isNewTx && Authenticator.getWalletOperation().isPendingOutTx(add.getAccountIndex(), tx.getHashAsString())){ 
        						removePendingOutTx(add.getAccountIndex(), tx.getHashAsString());
        						if(!didFireOnSendCoins){
        							Authenticator.fireOnBalanceChanged(tx, HowBalanceChanged.SentCoins, ConfidenceType.BUILDING);
        							didFireOnSendCoins = true;
        						}
        					}
        					break;
        				case PENDING:
        					if(!isNewTx)
        						;
        					/**
        					 * IMPORTANT:
	    					 * We add the transaction to isPendingOutTx list so after that it cannot enter here anymore.
        					 */
        					else if(!Authenticator.getWalletOperation().isPendingOutTx(add.getAccountIndex(), tx.getHashAsString())){
        						if(!accountsEffectedPending.contains(add.getAccountIndex())) accountsEffectedPending.add(add.getAccountIndex());
        						
        						// We only enter here once so transfer all the funds going out
        						subtractFromConfirmedBalance(add.getAccountIndex(), out.getValue());
        						
        						if(!didFireOnSendCoins){
	            					Authenticator.fireOnBalanceChanged(tx, HowBalanceChanged.SentCoins, ConfidenceType.PENDING);
	            					didFireOnSendCoins = true;
        						}
        					}
        					break;
        				case DEAD:
        					//Authenticator.getWalletOperation().addToConfirmedBalance(add.getAccountIndex(), out.getValue());
        					break;
        				}
        			}
    			}            			
        	}     
        	
        	// In case there are several inputs from the same account(PENDING)
        	if(accountsEffectedPending.size() > 0)
        		for(Integer acIndx:accountsEffectedPending)
        			addPendingOutTx(acIndx, tx.getHashAsString());
        }
       
    }
	
	//#####################################
	//
	//	Authenticator Wallet Operations
	//
	//#####################################
	
	/**Pushes the raw transaction the the Eligius mining pool
	 * @throws InsufficientMoneyException */
	public SendResult pushTxWithWallet(Transaction tx) throws IOException, InsufficientMoneyException{
		this.LOG.info("Broadcasting to network...");
		return this.mWalletWrapper.broadcastTrabsactionFromWallet(tx);
	}
	
	/**
	 * Derives a child public key from the master public key. Generates a new local key pair.
	 * Uses the two public keys to create a 2of2 multisig address. Saves key and address to json file.
	 * @throws JSONException 
	 * @throws NoSuchAlgorithmException 
	 * @throws AddressFormatException 
	 * @throws NoAccountCouldBeFoundException 
	 * @throws NoUnusedKeyException 
	 * @throws KeyIndexOutOfRangeException 
	 * @throws IncorrectPathException 
	 */
	private ATAddress generateNextP2SHAddress(int accountIdx, HierarchyAddressTypes addressType) throws NoSuchAlgorithmException, JSONException, AddressFormatException, NoUnusedKeyException, NoAccountCouldBeFoundException, KeyIndexOutOfRangeException, IncorrectPathException{
		PairedAuthenticator po = getPairingObjectForAccountIndex(accountIdx);
		return generateNextP2SHAddress(po.getPairingID(), addressType);
	}
	@SuppressWarnings({ "static-access", "deprecation" })
	private ATAddress generateNextP2SHAddress(String pairingID, HierarchyAddressTypes addressType) throws NoSuchAlgorithmException, JSONException, AddressFormatException, NoUnusedKeyException, NoAccountCouldBeFoundException, KeyIndexOutOfRangeException, IncorrectPathException{
		try {
			//Create a new key pair for wallet
			DeterministicKey walletHDKey = null;
			int walletAccountIdx = getAccountIndexForPairing(pairingID);
			int keyIndex = -1;
			if(addressType == HierarchyAddressTypes.External){
				walletHDKey = getNextExternalKey(walletAccountIdx, false);
				keyIndex = HierarchyUtils.getKeyIndexFromPath(walletHDKey.getPath()).num();//walletHDKey.getPath().get(walletHDKey.getPath().size() - 1).num();
			}
			/*else
				walletHDKey = getNextSavingsKey(this.getAccountIndexForPairing(pairingID));*/
			ECKey walletKey = new ECKey(walletHDKey.getPrivKeyBytes(), walletHDKey.getPubKey()); 
			
			//Derive the child public key from the master public key.
			PairedAuthenticator po = getPairingObject(pairingID);
			ECKey authKey = getPairedAuthenticatorKey(po, keyIndex);
			
			// generate P2SH
			ATAddress p2shAdd = getP2SHAddress(authKey, walletKey, keyIndex, walletAccountIdx, addressType);
			
			addAddressToWatch(p2shAdd.getAddressStr());			
			
			return p2shAdd;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
		
	}
	/**
	 * 
	 * 
	 * @param k1
	 * @param k2
	 * @param indxK - <b>is the same for both keys, make sure they are both HD derived</b>
	 * @param accountIndx
	 * @param addressType
	 * @return
	 */
	private ATAddress getP2SHAddress(ECKey k1, ECKey k2, int indxK, int accountIndx, HierarchyAddressTypes addressType){
		//network params
		NetworkParameters params = getNetworkParams();
		
		//Create a 2-of-2 multisig output script.
		List<ECKey> keys = ImmutableList.of(k1,k2);//childPubKey, walletKey);
		byte[] scriptpubkey = Script.createMultiSigOutputScript(2,keys);
		Script script = ScriptBuilder.createP2SHOutputScript(Utils.sha256hash160(scriptpubkey));
		
		//Create the address
		Address multisigaddr = Address.fromP2SHScript(params, script);
		
		// generate object
		String ret = multisigaddr.toString();
		ATAddress.Builder b = ATAddress.newBuilder();
						  b.setAccountIndex(accountIndx);//walletAccountIdx);
						  b.setAddressStr(ret);
						  b.setIsUsed(true);
						  b.setKeyIndex(indxK);//walletAccount.getLastExternalIndex());
						  b.setType(addressType);
		return b.build();
	}
	
	@SuppressWarnings("static-access")
	/**
	 * 
	 * @param outSelected
	 * @param to
	 * @param fee
	 * @param changeAdd
	 * @param np
	 * @return
	 * @throws AddressFormatException
	 * @throws JSONException
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 * @throws IllegalArgumentException
	 */
	public Transaction mkUnsignedTxWithSelectedInputs(ArrayList<TransactionOutput> outSelected, 
			ArrayList<TransactionOutput>to, 
			Coin fee, 
			String changeAdd,
			@Nullable NetworkParameters np) throws AddressFormatException, JSONException, IOException, NoSuchAlgorithmException, IllegalArgumentException {
		Transaction tx;
		if(np == null)
			tx = new Transaction(getNetworkParams());
		else
			tx = new Transaction(np);
		
		//Get total output
		Coin totalOut = Coin.ZERO;
		for (TransactionOutput out:to){
			totalOut = totalOut.add(out.getValue());
		}
		//Check minimum output
		if(totalOut.compareTo(Transaction.MIN_NONDUST_OUTPUT) < 0)
			throw new IllegalArgumentException("Tried to send dust with ensureMinRequiredFee set - no way to complete this");
		
		// add inputs
		Coin inAmount = Coin.ZERO;
		for (TransactionOutput input : outSelected){
            tx.addInput(input);
            inAmount = inAmount.add(input.getValue());
		}
		
		//Check in covers the out
		if(inAmount.compareTo(totalOut.add(fee)) < 0)
			throw new IllegalArgumentException("Insufficient funds! You cheap bastard !");
		
		//Add the outputs
		for (TransactionOutput output : to)
            tx.addOutput(output);
		
		//Add the change
		Address change = new Address(getNetworkParams(), changeAdd);
		Coin rest = inAmount.subtract(totalOut.add(fee));
		if(rest.compareTo(Transaction.MIN_NONDUST_OUTPUT) > 0){
			TransactionOutput changeOut = new TransactionOutput(this.mWalletWrapper.getNetworkParameters(), null, rest, change);
			tx.addOutput(changeOut);
			this.LOG.info("New Out Tx Sends " + totalOut.toFriendlyString() + 
							", Fees " + fee.toFriendlyString() + 
							", Rest " + rest.toFriendlyString() + 
							". From " + Integer.toString(tx.getInputs().size()) + " Inputs" +
							", To " + Integer.toString(tx.getOutputs().size()) + " Outputs.");
		}	
		else{
			fee = fee.add(rest);
			this.LOG.info("New Out Tx Sends " + totalOut.toFriendlyString() + 
					", Fees " + fee.toFriendlyString() + 
					". From " + Integer.toString(tx.getInputs().size()) + " Inputs" +
					", To " + Integer.toString(tx.getOutputs().size()) + " Outputs.");
		}
        
		// Check size.
        int size = tx.bitcoinSerialize().length;
        if (size > Transaction.MAX_STANDARD_TX_SIZE)
            throw new ExceededMaxTransactionSize();
		
		return tx;
	}
	
	/**
	 * 
	 * @param tx
	 * @param keys
	 * @return
	 * @throws KeyIndexOutOfRangeException
	 * @throws AddressFormatException
	 * @throws AddressNotWatchedByWalletException
	 */
	public Transaction signStandardTxWithAddresses(Transaction tx, Map<String,ATAddress> keys) throws KeyIndexOutOfRangeException, AddressFormatException, AddressNotWatchedByWalletException{
		Map<String,ECKey> keys2 = new HashMap<String,ECKey> ();
		for(String k:keys.keySet()){
			ECKey addECKey = getECKeyFromAccount(keys.get(k).getAccountIndex(), 
					HierarchyAddressTypes.External, 
					keys.get(k).getKeyIndex(),
					true);
			keys2.put(k, addECKey);
		}
		return signStandardTx(tx, keys2);
	}
	private Transaction signStandardTx(Transaction tx, Map<String,ECKey> keys){
		// sign
		for(int index=0;index < tx.getInputs().size(); index++){
			TransactionInput in = tx.getInput(index);
			TransactionOutput connectedOutput = in.getConnectedOutput();
			String addFrom = connectedOutput.getScriptPubKey().getToAddress(Authenticator.getWalletOperation().getNetworkParams()).toString();
			TransactionSignature sig = tx.calculateSignature(index, keys.get(addFrom), 
					connectedOutput.getScriptPubKey(), 
					Transaction.SigHash.ALL, 
					false);
			Script inputScript = ScriptBuilder.createInputScript(sig, keys.get(addFrom));
			in.setScriptSig(inputScript);
			
			try {
				in.getScriptSig().correctlySpends(tx, index, connectedOutput.getScriptPubKey(), false);
			} catch (ScriptException e) {
	            return null;
	        }
		}
		
		return tx;
	}
	
	
	//#####################################
	//
	//		 Keys handling
	//
	//#####################################
	
 	/**
 	 * Generate a new wallet account and writes it to the config file
 	 * @return
 	 * @throws IOException 
 	 */
 	private ATAccount generateNewAccount(NetworkType nt, String accountName, WalletAccountType type) throws IOException{
 		int accoutnIdx = authenticatorWalletHierarchy.generateNewAccount().getAccountIndex();
 		ATAccount.Builder b = ATAccount.newBuilder();
 						  b.setIndex(accoutnIdx);
 						 // b.setLastExternalIndex(0);
 						  //b.setLastInternalIndex(0);
 						  b.setConfirmedBalance(0);
 						  b.setUnConfirmedBalance(0);
 						  b.setNetworkType(nt.getValue());
 						  b.setAccountName(accountName);
 						  b.setAccountType(type);
						  
		writeHierarchyNextAvailableAccountID(accoutnIdx + 1); // update 
 	    configFile.addAccount(b.build());
 	    staticLogger.info("Generated new account at index, " + accoutnIdx);
 		return b.build();
 	}
 	
 	public ATAccount generateNewStandardAccount(NetworkType nt, String accountName) throws IOException{
		ATAccount ret = generateNewAccount(nt, accountName, WalletAccountType.StandardAccount);
		Authenticator.fireOnNewStandardAccountAdded();
		return ret;
	}

	
	/**
	 * Get the next {@link authenticator.protobuf.ProtoConfig.ATAddress ATAddress} object that is not been used, <b>it may been seen already</b><br>
	 * If the account is a <b>standard Pay-To-PubHash</b>, a Pay-To-PubHash address will be returned (prefix 1).<br>
	 *  If the account is a <b>P2SH</b>, a P2SH address will be returned (prefix 3).<br>
	 * 
	 * @param accountI
	 * @return
	 * @throws Exception
	 */
	public ATAddress getNextExternalAddress(int accountI) throws Exception{
		ATAccount acc = getAccount(accountI);
		if(acc.getAccountType() == WalletAccountType.StandardAccount)
			return getNextExternalPayToPubHashAddress(accountI,true);
		else
			return generateNextP2SHAddress(accountI, HierarchyAddressTypes.External);
	}
	
	/**
	 * 
	 * @param accountI
	 * @param shouldAddToWatchList
	 * @return
	 * @throws Exception
	 */
	private ATAddress getNextExternalPayToPubHashAddress(int accountI, boolean shouldAddToWatchList) throws Exception{
		DeterministicKey hdKey = getNextExternalKey(accountI,shouldAddToWatchList);
		ATAddress ret = getATAddreessFromAccount(accountI, HierarchyAddressTypes.External, HierarchyUtils.getKeyIndexFromPath(hdKey.getPath()).num());
		return ret;
	}
	
	/**
	 * 
	 * @param accountI
	 * @param shouldAddToWatchList
	 * @return
	 * @throws AddressFormatException
	 * @throws IOException
	 * @throws NoAccountCouldBeFoundException 
	 * @throws NoUnusedKeyException 
	 * @throws KeyIndexOutOfRangeException 
	 */
	private DeterministicKey getNextExternalKey(int accountI, boolean shouldAddToWatchList) throws AddressFormatException, IOException, NoUnusedKeyException, NoAccountCouldBeFoundException, KeyIndexOutOfRangeException{
		DeterministicKey ret = authenticatorWalletHierarchy.getNextKey(accountI, HierarchyAddressTypes.External);
		if(shouldAddToWatchList)
			addAddressToWatch( ret.toAddress(getNetworkParams()).toString() );
		return ret;
	}
	
	/**
	 * 
	 * @param accountIndex
	 * @param type
	 * @param addressKey
	 * @param iKnowAddressFromKeyIsNotWatched
	 * @return
	 * @throws KeyIndexOutOfRangeException
	 * @throws AddressFormatException
	 * @throws AddressNotWatchedByWalletException
	 */
	public ECKey getECKeyFromAccount(int accountIndex, HierarchyAddressTypes type, int addressKey, boolean iKnowAddressFromKeyIsNotWatched) throws KeyIndexOutOfRangeException, AddressFormatException, AddressNotWatchedByWalletException{
		DeterministicKey hdKey = getKeyFromAccount(accountIndex, type, addressKey, iKnowAddressFromKeyIsNotWatched);
		return ECKey.fromPrivate(hdKey.getPrivKeyBytes());
	}
	
	/**
	 * <b>The method remains public if any external method need it.</b><br>
	 * If u don't know if the corresponding Pay-To-PubHash address is watched, will throw exception. <br>
	 * If the key is part of a P2SH address, pass false for iKnowAddressFromKeyIsNotWatched<br>
	 * If the key was never created before, use {@link authenticator.WalletOperation#getNextExternalAddress getNextExternalAddress} instead.
	 * 
	 * @param accountIndex
	 * @param type
	 * @param addressKey
	 * @param iKnowAddressFromKeyIsNotWatched
	 * @return
	 * @throws KeyIndexOutOfRangeException
	 * @throws AddressFormatException
	 * @throws AddressNotWatchedByWalletException
	 */
	public DeterministicKey getKeyFromAccount(int accountIndex, 
			HierarchyAddressTypes type, 
			int addressKey, 
			boolean iKnowAddressFromKeyIsNotWatched) throws KeyIndexOutOfRangeException, AddressFormatException, AddressNotWatchedByWalletException{
		DeterministicKey ret = authenticatorWalletHierarchy.getKeyFromAcoount(accountIndex, type, addressKey);
		if(!iKnowAddressFromKeyIsNotWatched && !isWatchingAddress(ret.toAddress(getNetworkParams())))
			throw new AddressNotWatchedByWalletException("You are trying to get an unwatched address");
		return ret;
	}
	
	/**
	 * <b>WARNING</b> - This is a very costly operation !!<br> 
	 * Finds an address in the accounts, will throw exception if not.<br>
	 * Will only search for external address cause its reasonable that only they will be needing search with only the address string.<br>
	 * <br>
	 * <b>Assumes the address is already watched by the wallet</b>
	 * 
	 * @param addressStr
	 * @return {@link authenticator.protobuf.ProtoConfig.ATAddress ATAddress}
	 * @throws AddressWasNotFoundException
	 * @throws KeyIndexOutOfRangeException 
	 * @throws AddressFormatException 
	 * @throws JSONException 
	 * @throws NoSuchAlgorithmException 
	 */
	public ATAddress findAddressInAccounts(String addressStr) throws AddressWasNotFoundException, NoSuchAlgorithmException, JSONException, AddressFormatException, KeyIndexOutOfRangeException{
		List<ATAccount> accounts = getAllAccounts();
		int gapLookAhead = 30;
		while(gapLookAhead < 10000) // just arbitrary number
		{
			for(ATAccount acc:accounts){
				for(int i = gapLookAhead - 30 ; i < gapLookAhead; i++)
				{
					try{
						ATAddress add = getATAddreessFromAccount(acc.getIndex(), HierarchyAddressTypes.External, i);
						if(add.getAddressStr().equals(addressStr))
							return add;
					}
					catch (AddressNotWatchedByWalletException e) {
						break; // address is not watched which means we reached the end on the generated addresses
					}
				}
			}
			gapLookAhead += 30;
		}
		throw new AddressWasNotFoundException("Cannot find address in accounts");
	}
	
	/**
	 * get addresses from a particular account and his chain
	 * 
	 * @param accountIndex
	 * @param type
	 * @param limit
	 * @return
	 * @throws NoSuchAlgorithmException
	 * @throws JSONException
	 * @throws AddressFormatException
	 * @throws KeyIndexOutOfRangeException 
	 * @throws AddressNotWatchedByWalletException 
	 */
	public List<ATAddress> getATAddreessesFromAccount(int accountIndex, HierarchyAddressTypes type,int standOff, int limit) throws NoSuchAlgorithmException, JSONException, AddressFormatException, KeyIndexOutOfRangeException, AddressNotWatchedByWalletException{
		List<ATAddress> ret = new ArrayList<ATAddress>();
		if(type == HierarchyAddressTypes.External)
			for(int i = standOff;i <= limit; i++)//Math.min(limit==-1? acc.getLastExternalIndex():limit, acc.getLastExternalIndex()) ; i++){
			{
				ret.add(getATAddreessFromAccount(accountIndex, type, i));
			}
		
		return ret;
	}
	
	/**
	 * Gets a particular address from an account.<br>
	 * Will assert that the address was created before, if not will throw exception.
	 * 
	 * 
	 * @param accountIndex
	 * @param type
	 * @param addressKey
	 * @return
	 * @throws NoSuchAlgorithmException
	 * @throws JSONException
	 * @throws AddressFormatException
	 * @throws KeyIndexOutOfRangeException 
	 * @throws AddressNotWatchedByWalletException 
	 */
	@SuppressWarnings("static-access")
	public ATAddress getATAddreessFromAccount(int accountIndex, HierarchyAddressTypes type, int addressKey) throws NoSuchAlgorithmException, JSONException, AddressFormatException, KeyIndexOutOfRangeException, AddressNotWatchedByWalletException{		
		ATAccount acc = getAccount(accountIndex);
		ATAddress.Builder atAdd = ATAddress.newBuilder();
						  atAdd.setAccountIndex(accountIndex);
						  atAdd.setKeyIndex(addressKey);
						  atAdd.setType(type);
						  /**
						   * Standard Pay-To-PubHash
						   */
						  if(acc.getAccountType() == WalletAccountType.StandardAccount){
							  DeterministicKey hdKey = getKeyFromAccount(accountIndex,type,addressKey, false);
							  atAdd.setAddressStr(hdKey.toAddress(getNetworkParams()).toString());
						  }
						  else{
							  /**
							   * P2SH
							   */
							PairedAuthenticator  po = getPairingObjectForAccountIndex(accountIndex);
							
							// Auth key
							ECKey authKey = getPairedAuthenticatorKey(po, addressKey);
							
							// wallet key
							ECKey walletKey = getECKeyFromAccount(accountIndex, type, addressKey, true);
							
							//get address
							ATAddress add = getP2SHAddress(authKey, walletKey, addressKey, accountIndex, type);
							
							atAdd.setAddressStr(add.getAddressStr());
						  }
						  
						  
		return atAdd.build();
	}
	
	public List<ATAccount> getAllAccounts(){
		return configFile.getAllAccounts();
	}
	
	public ATAccount getAccount(int index){
		return configFile.getAccount(index);
	} 

	/**
	 * Remove account from config file.<br>
	 * <b>Will assert at least one account remains after the removal</b>
	 * 
	 * @param index
	 * @throws IOException
	 */
	public void removeAccount(int index) throws IOException{
		PairedAuthenticator po =  getPairingObjectForAccountIndex(index);
		if(po != null)
			removePairingObject(po.getPairingID());
		configFile.removeAccount(index);
		Authenticator.fireOnAccountDeleted(index);
	}
	
	public ATAccount getAccountByName(String name){
		List<ATAccount> all = getAllAccounts();
		for(ATAccount acc: all)
			if(acc.getAccountName().equals(name))
				return acc;
		
		return null;
	}
	
	/**
	 * 
	 * @param accountIndex
	 * @param addressesType
	 * @param limit
	 * @return
	 * @throws NoSuchAlgorithmException
	 * @throws JSONException
	 * @throws AddressFormatException
	 * @throws KeyIndexOutOfRangeException 
	 * @throws AddressNotWatchedByWalletException 
	 */
	public ArrayList<String> getAccountNotUsedAddress(int accountIndex, HierarchyAddressTypes addressesType, int limit) throws NoSuchAlgorithmException, JSONException, AddressFormatException, KeyIndexOutOfRangeException, AddressNotWatchedByWalletException{
		ArrayList<String> ret = new ArrayList<String>();
		ATAccount account = getAccount(accountIndex);
		if(addressesType == HierarchyAddressTypes.External)
		for(int i=0;i < limit; i++)//Math.min(account.getLastExternalIndex(), limit == -1? account.getLastExternalIndex():limit); i++){
		{
			if(account.getUsedExternalKeysList().contains(i))
				continue;
			ATAddress a = getATAddreessFromAccount(accountIndex,addressesType, i);
			ret.add(a.getAddressStr());
		}
		
		return ret;
	}
	
	/**
	 * Will return all used address of the account
	 * 
	 * @param accountIndex
	 * @param addressesType
	 * @return
	 * @throws NoSuchAlgorithmException
	 * @throws JSONException
	 * @throws AddressFormatException
	 * @throws KeyIndexOutOfRangeException
	 * @throws AddressNotWatchedByWalletException
	 */
	public ArrayList<ATAddress> getAccountUsedAddresses(int accountIndex, HierarchyAddressTypes addressesType) throws NoSuchAlgorithmException, JSONException, AddressFormatException, KeyIndexOutOfRangeException, AddressNotWatchedByWalletException{
		ArrayList<ATAddress> ret = new ArrayList<ATAddress>();
		ATAccount acc = getAccount(accountIndex);
		if(addressesType == HierarchyAddressTypes.External){
			List<Integer> used = acc.getUsedExternalKeysList();
			for(Integer i:used){
				ATAddress a = getATAddreessFromAccount(accountIndex,addressesType, i);
				ret.add(a);
			}
		}
		return ret;
	}
	
	public ArrayList<String> getAccountUsedAddressesString(int accountIndex, HierarchyAddressTypes addressesType) throws NoSuchAlgorithmException, JSONException, AddressFormatException, KeyIndexOutOfRangeException, AddressNotWatchedByWalletException{
		ArrayList<ATAddress> addresses = getAccountUsedAddresses(accountIndex, addressesType);
		ArrayList<String> ret = new ArrayList<String>();
		for(ATAddress add: addresses)
			ret.add(add.getAddressStr());
		return ret;
	}
	
	/**
	 * Returns all addresses from an account in a ArrayList of strings
	 * 
	 * @param accountIndex
	 * @param addressesType
	 * @param limit
	 * @return ArrayList of strings
	 * @throws NoSuchAlgorithmException
	 * @throws JSONException
	 * @throws AddressFormatException
	 * @throws KeyIndexOutOfRangeException 
	 * @throws AddressNotWatchedByWalletException 
	 */
	public ArrayList<String> getAccountAddresses(int accountIndex, HierarchyAddressTypes addressesType, int limit) throws NoSuchAlgorithmException, JSONException, AddressFormatException, KeyIndexOutOfRangeException, AddressNotWatchedByWalletException{
		ArrayList<String> ret = new ArrayList<String>();
		if(addressesType == HierarchyAddressTypes.External)
		for(int i=0;i < limit; i ++) //Math.min(account.getLastExternalIndex(), limit == -1? account.getLastExternalIndex():limit); i++){
		{
			ATAddress a = getATAddreessFromAccount(accountIndex,addressesType, i);
			ret.add(a.getAddressStr());
		}
		
		return ret;
	}

	public ATAccount setAccountName(String newName, int index) throws IOException{
		assert(newName.length() > 0);
		ATAccount.Builder b = ATAccount.newBuilder(getAccount(index));
		b.setAccountName(newName);
		configFile.updateAccount(b.build());
		Authenticator.fireOnAccountBeenModified(index);
		return b.build();
	}
	
	public void markAddressAsUsed(int accountIdx, int addIndx, HierarchyAddressTypes type) throws IOException, NoSuchAlgorithmException, JSONException, AddressFormatException, NoAccountCouldBeFoundException, KeyIndexOutOfRangeException, AddressNotWatchedByWalletException{
		configFile.markAddressAsUsed(accountIdx, addIndx,type);
		authenticatorWalletHierarchy.markAddressAsUsed(accountIdx, addIndx, type);
		ATAddress add = getATAddreessFromAccount(accountIdx, type, addIndx);
		this.LOG.info("Marked " + add.getAddressStr() + " as used.");
	}

	public int getHierarchyNextAvailableAccountID(){
		return configFile.getHierarchyNextAvailableAccountID();
	}

	public void writeHierarchyNextAvailableAccountID(int i) throws IOException{
		configFile.writeHierarchyNextAvailableAccountID(i);
	}
	
	/*public byte[] getHierarchySeed() throws FileNotFoundException, IOException{
		return configFile.getHierarchySeed();
	}
	
	public void writeHierarchySeed(byte[] seed) throws FileNotFoundException, IOException{
		configFile.writeHierarchySeed(seed);
	}*/
	
	//#####################################
	//
	//		 Pairing handling
	//
	//#####################################
	
	public PairedAuthenticator getPairingObjectForAccountIndex(int accIdx){
		List<PairedAuthenticator> all = new ArrayList<PairedAuthenticator>();
		try {
			all = getAllPairingObjectArray();
		} catch (IOException e) { e.printStackTrace(); }
		for(PairedAuthenticator po: all)
		{
			if(po.getWalletAccountIndex() == accIdx)
			{
				return po;
			}
		}
		return null;
	}
	
	public int getAccountIndexForPairing(String PairID){
		List<PairedAuthenticator> all = new ArrayList<PairedAuthenticator>();
		try {
			all = getAllPairingObjectArray();
		} catch (IOException e) { e.printStackTrace(); }
		for(PairedAuthenticator po: all)
		{
			if(po.getPairingID().equals(PairID))
			{
				return po.getWalletAccountIndex();
			}
		}
		return -1;
	}
	
	/**
	 * Returns all addresses from a pairing in a ArrayList of strings
	 * 
	 * @param accountIndex
	 * @param addressesType
	 * @return ArrayList of strings
	 * @throws NoSuchAlgorithmException
	 * @throws JSONException
	 * @throws AddressFormatException
	 * @throws KeyIndexOutOfRangeException 
	 * @throws AddressNowWatchedByWalletException 
	 */
	public ArrayList<String> getPairingAddressesArray(String PairID, HierarchyAddressTypes addressesType, int limit) throws NoSuchAlgorithmException, JSONException, AddressFormatException, KeyIndexOutOfRangeException, AddressNotWatchedByWalletException{
		int accIndex = getAccountIndexForPairing(PairID);
		return getAccountAddresses(accIndex,addressesType, limit);
	}
	
	/**Returns the Master Public Key and Chaincode as an ArrayList object */
	public ArrayList<String> getPublicKeyAndChain(String pairingID){
		List<PairedAuthenticator> all = new ArrayList<PairedAuthenticator>();
		try {
			all = getAllPairingObjectArray();
		} catch (IOException e) { e.printStackTrace(); }
		
		ArrayList<String> ret = new ArrayList<String>();
		for(PairedAuthenticator o:all)
		{
			if(o.getPairingID().equals(pairingID))
			{
				ret.add(o.getMasterPublicKey());
				ret.add(o.getChainCode());
			}
		}
		return ret;
	}
	
	public ECKey getPairedAuthenticatorKey(PairedAuthenticator po, int keyIndex){
		ArrayList<String> keyandchain = getPublicKeyAndChain(po.getPairingID());
		byte[] key = BAUtils.hexStringToByteArray(keyandchain.get(0));
		byte[] chain = BAUtils.hexStringToByteArray(keyandchain.get(1));
		HDKeyDerivation HDKey = null;
  		DeterministicKey mPubKey = HDKey.createMasterPubKeyFromBytes(key, chain);
  		DeterministicKey childKey = HDKey.deriveChildKey(mPubKey, keyIndex);
  		byte[] childpublickey = childKey.getPubKey();
		ECKey authKey = new ECKey(null, childpublickey);
		
		return authKey;
	}
	
	/**Returns the number of key pairs in the wallet */
	public long getKeyNum(String pairID){
		List<PairedAuthenticator> all = new ArrayList<PairedAuthenticator>();
		try {
			all = getAllPairingObjectArray();
		} catch (IOException e) { e.printStackTrace(); }
		for(PairedAuthenticator o:all)
		{
			if(o.getPairingID().equals(pairID))
				return o.getKeysN();
		}
		return 0;
	}
	
	/**Pulls the AES key from file and returns it  */
	public String getAESKey(String pairID) {
		List<PairedAuthenticator> all = new ArrayList<PairedAuthenticator>();
		try {
			all = getAllPairingObjectArray();
		} catch (IOException e) { e.printStackTrace(); }
		for(PairedAuthenticator o:all)
		{
			if(o.getPairingID().equals(pairID))
				return o.getAesKey();
		}
		return "";
	}
		
	public List<PairedAuthenticator> getAllPairingObjectArray() throws FileNotFoundException, IOException
	{
		return configFile.getAllPairingObjectArray();
	}
	
	public PairedAuthenticator getPairingObject(String pairID)
	{
		List<PairedAuthenticator> all = new ArrayList<PairedAuthenticator>();
		try {
			all = getAllPairingObjectArray();
		} catch (IOException e) { e.printStackTrace(); }
		for(PairedAuthenticator po: all)
			if(po.getPairingID().equals(pairID))
				return po;
		return null;
	}
	
	public ArrayList<String> getPairingIDs()
	{
		List<PairedAuthenticator> all = new ArrayList<PairedAuthenticator>();
		try {
			all = getAllPairingObjectArray();
		} catch (IOException e) { e.printStackTrace(); }
		ArrayList<String> ret = new ArrayList<String>();
		for(PairedAuthenticator o:all)
			ret.add(o.getPairingID());
		return ret;
	}
	
	public void generateNewPairing(String authMpubkey, 
			String authhaincode, 
			String sharedAES, 
			String GCM, 
			String pairingID, 
			String pairName,
			NetworkType nt) throws IOException{
		int accountID = generateNewAccount(nt, pairName, WalletAccountType.AuthenticatorAccount).getIndex();
		writePairingData(authMpubkey,authhaincode,sharedAES,GCM,pairingID,accountID);
		Authenticator.fireOnNewPairedAuthenticator();
	}
	
	private void writePairingData(String mpubkey, String chaincode, String key, String GCM, String pairingID, int accountIndex) throws IOException{
		configFile.writePairingData(mpubkey, chaincode, key, GCM, pairingID, accountIndex);
	}

	/**
	 * 
	 * @param pairingID
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	private void removePairingObject(String pairingID) throws FileNotFoundException, IOException{
		configFile.removePairingObject(pairingID);
	}
	
	/*public void addGeneratedAddressForPairing(String pairID, String addr, int indexWallet, int indexAuth) throws FileNotFoundException, IOException, ParseException{
		configFile.addGeneratedAddressForPairing(pairID,  addr, indexWallet, indexAuth);
	}*/
	
	//#####################################
	//
	//		 Balances handling
	//
	//#####################################
	
	public Coin getConfirmedBalance(int accountIdx){
		long balance = configFile.getConfirmedBalace(accountIdx);
		return Coin.valueOf(balance);
	}
	
	/**
	 * Will return updated balance
	 * 
	 * @param accountIdx
	 * @param amount
	 * @return
	 * @throws IOException
	 */
	public Coin addToConfirmedBalance(int accountIdx, Coin amount) throws IOException{
		Coin old = getConfirmedBalance(accountIdx);
		return setConfirmedBalance(accountIdx, old.add(amount));
	}
	
	/**
	 * Will return updated balance
	 * 
	 * @param accountIdx
	 * @param amount
	 * @return
	 * @throws IOException
	 */
	public Coin subtractFromConfirmedBalance(int accountIdx, Coin amount) throws IOException{
		Coin old = getConfirmedBalance(accountIdx);
		assert(old.compareTo(amount) >= 0);
		return setConfirmedBalance(accountIdx, old.subtract(amount));
	}
	
	/**
	 * Will return the updated balance
	 * 
	 * @param accountIdx
	 * @param newBalance
	 * @return
	 * @throws IOException
	 */
	public Coin setConfirmedBalance(int accountIdx, Coin newBalance) throws IOException{
		long balance = configFile.writeConfirmedBalace(accountIdx, newBalance.longValue());
		return Coin.valueOf(balance);
	}
	
	public Coin getUnConfirmedBalance(int accountIdx){
		long balance = configFile.getUnConfirmedBalace(accountIdx);
		return Coin.valueOf(balance);
	}
	
	/**
	 * Will return updated balance
	 * 
	 * @param accountIdx
	 * @param amount
	 * @return
	 * @throws IOException
	 */
	public Coin addToUnConfirmedBalance(int accountIdx, Coin amount) throws IOException{
		Coin old = getUnConfirmedBalance(accountIdx);
		return setUnConfirmedBalance(accountIdx, old.add(amount));
	}
	
	/**
	 * Will return updated balance
	 * 
	 * @param accountIdx
	 * @param amount
	 * @return
	 * @throws IOException
	 */
	public Coin subtractFromUnConfirmedBalance(int accountIdx, Coin amount) throws IOException{
		Coin old = getUnConfirmedBalance(accountIdx);
		assert(old.compareTo(amount) >= 0);
		return setUnConfirmedBalance(accountIdx, old.subtract(amount));
	}
	
	/**
	 * Will return the updated balance
	 * 
	 * @param accountIdx
	 * @param newBalance
	 * @return
	 * @throws IOException
	 */
	public Coin setUnConfirmedBalance(int accountIdx, Coin newBalance) throws IOException{
		long balance = configFile.writeUnConfirmedBalace(accountIdx, newBalance.longValue());
		return Coin.valueOf(balance);
	}
	
	/**
	 * Will return the updated confirmed balance
	 * 
	 * @param accountId
	 * @param amount
	 * @return
	 * @throws IOException 
	 */
	public Coin moveFundsFromUnconfirmedToConfirmed(int accountId,Coin amount) throws IOException{
		Coin beforeConfirmed = getConfirmedBalance(accountId);
		Coin beforeUnconf = getUnConfirmedBalance(accountId);
		assert(beforeUnconf.compareTo(amount) >= 0);
		//
		Coin afterConfirmed = beforeConfirmed.add(amount);
		Coin afterUnconfirmed = beforeUnconf.subtract(amount);
		
		setConfirmedBalance(accountId,afterConfirmed);
		setUnConfirmedBalance(accountId,afterUnconfirmed);
		
		return afterConfirmed;
	}
	
	/**
	 * Will return the updated unconfirmed balance
	 * 
	 * @param accountId
	 * @param amount
	 * @return
	 * @throws IOException 
	 */
	public Coin moveFundsFromConfirmedToUnConfirmed(int accountId,Coin amount) throws IOException{
		Coin beforeConfirmed = getConfirmedBalance(accountId);
		Coin beforeUnconf = getUnConfirmedBalance(accountId);
		assert(beforeConfirmed.compareTo(amount) >= 0);
		//
		Coin afterConfirmed = beforeConfirmed.subtract(amount);
		Coin afterUnconfirmed = beforeUnconf.add(amount);
		
		setConfirmedBalance(accountId,afterConfirmed);
		setUnConfirmedBalance(accountId,afterUnconfirmed);
		
		return afterUnconfirmed;
	}
	
	public ArrayList<Transaction> filterHistoryByAccount (int accountIndex) throws NoSuchAlgorithmException, JSONException, AddressFormatException, KeyIndexOutOfRangeException, AddressNotWatchedByWalletException{
		ArrayList<Transaction> filteredHistory = new ArrayList<Transaction>();
		ArrayList<String> usedExternalAddressList = getAccountUsedAddressesString(accountIndex, HierarchyAddressTypes.External);
		Set<Transaction> fullTxSet = Main.bitcoin.wallet().getTransactions(false);
    	for (Transaction tx : fullTxSet){
    		for (int a=0; a<tx.getInputs().size(); a++){
    			String address = tx.getInput(a).getConnectedOutput().getScriptPubKey().getToAddress(Authenticator.getWalletOperation().getNetworkParams()).toString();
    			for (String addr : usedExternalAddressList){
    				if (addr.equals(address)){
    					if (!filteredHistory.contains(tx)){filteredHistory.add(tx);}
    				}
    			}
    			//We need to do the same thing here for internal addresses
    			
    		}
    		for (int b=0; b<tx.getOutputs().size(); b++){
    			String address = tx.getOutput(b).getScriptPubKey().getToAddress(Authenticator.getWalletOperation().getNetworkParams()).toString();
    			for (String addr : usedExternalAddressList){
    				if (addr.equals(address)){
    					if (!filteredHistory.contains(tx)){filteredHistory.add(tx);}
    				}
    			}
    			//Same thing here, we need to check internal addresses as well.
    		}
    	}	
		return filteredHistory;
	}
	
	
	//#####################################
	//
	//		Pending Requests Control
	//
	//#####################################
		
		public static void addPendingRequest(PendingRequest req) throws FileNotFoundException, IOException{
			configFile.writeNewPendingRequest(req);
		}
		
		public static void removePendingRequest(PendingRequest req) throws FileNotFoundException, IOException{
			staticLogger.info("Removed pending request: " + req.getRequestID());
			configFile.removePendingRequest(req);
		}
		
		public static int getPendingRequestSize(){
			try {
				return getPendingRequests().size();
			} catch (FileNotFoundException e) { } catch (IOException e) { }
			return 0;
		}
		
		public static List<PendingRequest> getPendingRequests() throws FileNotFoundException, IOException{
			return configFile.getPendingRequests();
		}
		
		public String pendingRequestToString(PendingRequest op){
			String type = "";
			switch(op.getOperationType()){
				case Pairing:
						type = "Pairing";
					break;
				case Unpair:
						type = "Unpair";
					break;
				case SignAndBroadcastAuthenticatorTx:
						type = "Sign and broadcast Auth. Tx";
					break;
				case BroadcastNormalTx:
						type = "Broadcast normal Tx";	
					break;
				case updateIpAddressesForPreviousMessage:
						type = "Update Ip address from previous message";
					break;
			}
			
			PairedAuthenticator po = getPairingObject(op.getPairingID());
			ATAccount acc = getAccount(po.getWalletAccountIndex());
			String pairingName = acc.getAccountName();			
			return pairingName + ": " + type + "  ---  " + op.getRequestID();
		}
		
	//#####################################
	//
	//		Pending transactions 
	//
	//#####################################
		public List<String> getPendingOutTx(int accountIdx){
			return configFile.getPendingOutTx(accountIdx);
		}
		
		public void addPendingOutTx(int accountIdx, String txID) throws FileNotFoundException, IOException{
			configFile.addPendingOutTx(accountIdx,txID);
		}
		
		public void removePendingOutTx(int accountIdx, String txID) throws FileNotFoundException, IOException{
			configFile.removePendingOutTx(accountIdx,txID);
		}
		
		public boolean isPendingOutTx(int accountIdx, String txID) throws FileNotFoundException, IOException{
			List<String> all = getPendingOutTx(accountIdx);
			for(String tx:all)
				if(tx.equals(txID))
					return true;
			return false;
		}
		
		public List<String> getPendingInTx(int accountIdx){
			return configFile.getPendingInTx(accountIdx);
		}
		
		public void addPendingInTx(int accountIdx, String txID) throws FileNotFoundException, IOException{
			configFile.addPendingInTx(accountIdx,txID);
		}
		
		public void removePendingInTx(int accountIdx, String txID) throws FileNotFoundException, IOException{
			configFile.removePendingInTx(accountIdx,txID);
		}
		
		public boolean isPendingInTx(int accountIdx, String txID) throws FileNotFoundException, IOException{
			List<String> all = getPendingInTx(accountIdx);
			for(String tx:all)
				if(tx.equals(txID))
					return true;
			return false;
		}
		
	//#####################################
	//
	//		One name
	//
	//#####################################
		
		public AuthenticatorConfiguration.ConfigOneNameProfile getOnename(){
			try {
				AuthenticatorConfiguration.ConfigOneNameProfile on = configFile.getOnename();
				if(on.getOnename().length() == 0)
					return null;
				return on;
			} catch (IOException e) { e.printStackTrace(); }
			return null;
		}
		
		public void writeOnename(AuthenticatorConfiguration.ConfigOneNameProfile one) throws FileNotFoundException, IOException{
			configFile.writeOnename(one);
		}
		
	//#####################################
	//
	//		Active account Control
	//
	//#####################################
		
		/**
		 * Surrounded with try & catch because we access it a lot.
		 * 
		 * @return
		 */
		public AuthenticatorConfiguration.ConfigActiveAccount getActiveAccount() {
			try {
				return configFile.getActiveAccount();
			} catch (IOException e) { e.printStackTrace(); }
			return null;
		}

		/**
		 * Sets the active account according to account index, returns the active account.<br>
		 * Will return null in case its not successful
		 * @param accountIdx
		 * @return
		 * @throws IOException 
		 * @throws FileNotFoundException 
		 */
		public ATAccount setActiveAccount(int accountIdx){
			ATAccount acc = getAccount(accountIdx);
			AuthenticatorConfiguration.ConfigActiveAccount.Builder b1 = AuthenticatorConfiguration.ConfigActiveAccount.newBuilder();
			b1.setActiveAccount(acc);
			if(acc.getAccountType() == WalletAccountType.AuthenticatorAccount){
				PairedAuthenticator p = Authenticator.getWalletOperation().getPairingObjectForAccountIndex(acc.getIndex());
				b1.setPairedAuthenticator(p);
			}
			try {
				writeActiveAccount(b1.build());
				return acc;
			} catch (IOException e) { e.printStackTrace(); }
			return null;
		}

		private void writeActiveAccount(AuthenticatorConfiguration.ConfigActiveAccount acc) throws FileNotFoundException, IOException{

			configFile.writeActiveAccount(acc);
		}
		
	//#####################################
  	//
  	//	Regular Bitocoin Wallet Operations
  	//
  	//#####################################
    
    public NetworkParameters getNetworkParams()
	{
    	assert(mWalletWrapper != null);
		return mWalletWrapper.getNetworkParams();
	}
    
    public boolean isWatchingAddress(Address address) throws AddressFormatException{
    	return isWatchingAddress(address.toString());
    }
    public boolean isWatchingAddress(String address) throws AddressFormatException
	{
    	assert(mWalletWrapper != null);
		return mWalletWrapper.isAuthenticatorAddressWatched(address);
	}
    
    public boolean isTransactionOutputMine(TransactionOutput out)
	{
    	assert(mWalletWrapper != null);
		return mWalletWrapper.isTransactionOutputMine(out);
	}
    
    public void addAddressToWatch(String address) throws AddressFormatException
	{
    	assert(mWalletWrapper != null);
    	if(!mWalletWrapper.isAuthenticatorAddressWatched(address)){
    		mWalletWrapper.addAddressToWatch(address);
        	this.LOG.info("Added address to watch: " + address);
    	}
	}
    
	public void connectInputs(List<TransactionInput> inputs)
	{
		assert(mWalletWrapper != null);
		LinkedList<TransactionOutput> unspentOutputs = mWalletWrapper.getWatchedOutputs();
		for(TransactionOutput out:unspentOutputs)
			for(TransactionInput in:inputs){
				String hashIn = in.getOutpoint().getHash().toString();
				String hashOut = out.getParentTransaction().getHash().toString();
				if(hashIn.equals(hashOut)){
					in.connect(out);
					break;
				}
			}
	}
	
	public void disconnectInputs(List<TransactionInput> inputs){
		for(TransactionInput input:inputs)
			input.disconnect();
	}
	
	public SendResult sendCoins(Wallet.SendRequest req) throws InsufficientMoneyException
	{
		assert(mWalletWrapper != null);
		this.LOG.info("Sent Tx: " + req.tx.getHashAsString());
		return mWalletWrapper.sendCoins(req);
	}
	
	public void addEventListener(WalletEventListener listener)
	{
		assert(mWalletWrapper != null);
		mWalletWrapper.addEventListener(listener);
	}
	
	public ECKey findKeyFromPubHash(byte[] pubkeyHash){
		assert(mWalletWrapper != null);
		return mWalletWrapper.findKeyFromPubHash(pubkeyHash);
	}
	
	public List<Transaction> getRecentTransactions(){
		assert(mWalletWrapper != null);
		return mWalletWrapper.getRecentTransactions();
	}
	
	public ArrayList<TransactionOutput> selectOutputs(Coin value, ArrayList<TransactionOutput> candidates)
	{
		LinkedList<TransactionOutput> outs = new LinkedList<TransactionOutput> (candidates);
		DefaultCoinSelector selector = new DefaultCoinSelector();
		CoinSelection cs = selector.select(value, outs);
		Collection<TransactionOutput> gathered = cs.gathered;
		ArrayList<TransactionOutput> ret = new ArrayList<TransactionOutput>(gathered);
	
		return ret;
	}
	
	public ArrayList<TransactionOutput> getUnspentOutputsForAccount(int accountIndex) throws ScriptException, NoSuchAlgorithmException, AddressWasNotFoundException, JSONException, AddressFormatException, KeyIndexOutOfRangeException{
		LinkedList<TransactionOutput> all = mWalletWrapper.getWatchedOutputs();
		ArrayList<TransactionOutput> ret = new ArrayList<TransactionOutput>();
		for(TransactionOutput unspentOut:all){
			ATAddress add = findAddressInAccounts(unspentOut.getScriptPubKey().getToAddress(getNetworkParams()).toString());
			if(add.getAccountIndex() == accountIndex)
				ret.add(unspentOut);
		}
		return ret;
	}
 
	public ArrayList<TransactionOutput> getUnspentOutputsForAddresses(ArrayList<String> addressArr)
	{
		return mWalletWrapper.getUnspentOutputsForAddresses(addressArr);
	}
	
	public Coin getTxValueSentToMe(Transaction tx){
		return mWalletWrapper.getTxValueSentToMe(tx);
	}
	
	public Coin getTxValueSentFromMe(Transaction tx){
		return mWalletWrapper.getTxValueSentFromMe(tx);
	}
	
	public void decryptWallet(String password){
		mWalletWrapper.decryptWallet(password);
	}
	
	public void encryptWallet(String password){
		mWalletWrapper.encryptWallet(password);
	}
	
	public boolean isWalletEncrypted(){
		return mWalletWrapper.isWalletEncrypted();
	}
}


