package authenticator.ui_helpers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.control.ComboBox;
import authenticator.Authenticator;
import authenticator.protobuf.ProtoConfig.ConfigAuthenticatorWallet.PairedAuthenticator;

public class ComboBoxHelper {

	
	@SuppressWarnings("unchecked")
	public static Map<String,String> populateComboWithPairingNames(ComboBox cmb)
	{
		List<PairedAuthenticator> arr = new ArrayList<PairedAuthenticator>();
		try {
			arr = Authenticator.getWalletOperation().getAllPairingObjectArray();
		} catch (IOException e) { e.printStackTrace(); }
		Map<String,String> pairNameToId = new HashMap<String,String>();
		cmb.getItems().clear();
		for(PairedAuthenticator po:arr)
		{
			if(!pairNameToId.containsKey(po.getPairingName()))
				pairNameToId.put(po.getPairingName(), po.getPairingID());
		}
		cmb.getItems().addAll(pairNameToId.keySet());
		return pairNameToId;
	}
}