package wallettemplate;

import static wallettemplate.utils.GuiUtils.informationalAlert;

import java.awt.Desktop;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.controlsfx.control.textfield.CustomTextField;
import org.controlsfx.dialog.Dialogs;

import wallettemplate.utils.BaseUI;
import wallettemplate.utils.GuiUtils;
import authenticator.Authenticator;
import authenticator.BAApplicationParameters;
import authenticator.BAApplicationParameters.NetworkType;
import authenticator.BipSSS.BipSSS;
import authenticator.BipSSS.BipSSS.EncodingFormat;
import authenticator.BipSSS.BipSSS.Share;
import authenticator.Utils.EncodingUtils;
import authenticator.protobuf.ProtoConfig.AuthenticatorConfiguration.ATAccount;

import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.crypto.DeterministicKey;
import com.google.bitcoin.crypto.HDKeyDerivation;
import com.google.bitcoin.params.MainNetParams;
import com.google.bitcoin.wallet.DeterministicSeed;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.Service.State;

import de.jensd.fx.fontawesome.AwesomeDude;
import de.jensd.fx.fontawesome.AwesomeIcon;
import javafx.animation.Animation;
import javafx.application.Platform;
import javafx.beans.binding.NumberBinding;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;

public class StartupController  extends BaseUI{
	
	@FXML private Pane MainPane;
	@FXML private Pane CreateAccountPane;
	@FXML private Pane BackupNewWalletPane;
	@FXML private Pane ExplanationPane1;
	@FXML private Pane RestoreFromSeedPane;
	@FXML private Pane Pane6;
	@FXML private Pane SSSBackupPane;
	@FXML private Pane MainRestorePane;
	@FXML private Hyperlink hlpw;
	@FXML private WebView browser;
	@FXML private Button btnNewWallet;
	@FXML private Button btnContinue1;
	@FXML private Button btnBack1;
	@FXML private Button btnContinue2;
	@FXML private Button btnContinue3;
	@FXML private Button btnBack2;
	@FXML private Button btnBack3;
	@FXML private Button btnBack4;
	@FXML private Button btnBack5;
	@FXML private Label lblMinimize;
	@FXML private Label lblClose;
	@FXML private Button btnDone;
	@FXML private TextField txAccount;
	@FXML private PasswordField txPW1;
	@FXML private PasswordField txPW2;
	@FXML private Label lblSeed;
	@FXML private CheckBox ckSeed;
	@FXML private CheckBox chkTestNet;
	private double xOffset = 0;
	private double yOffset = 0;
	private String mnemonic = "";
	@FXML private TextField txPieces;
	@FXML private TextField txThreshold;
	@FXML private ListView lvSSS;
	@FXML private Button btnMnemonic;
	@FXML private Button btnScanQR;
	@FXML private Pane BA1;
	@FXML private Pane BA2;
	@FXML private Pane BA3;
	@FXML private Pane BA4;
	@FXML private Hyperlink hlFinished;
	@FXML private ImageView imgSSS;
	@FXML private Button btnSSS;
	private DeterministicSeed seed;
	NetworkParameters params = MainNetParams.get();
	Authenticator auth;
	Wallet wallet;
	
	/**
	 * Will be set before the Stage is launched so we could define the wallet files.
	 */
	static public BAApplicationParameters appParams;
	

	 public void initialize() {
		 super.initialize(StartupController.class);
		 assert(appParams != null);
		 
		 // set testnet checkbox
		 if(appParams.getBitcoinNetworkType() == NetworkType.MAIN_NET)
			 chkTestNet.setSelected(false);
		 else
			 chkTestNet.setSelected(true);
		 chkTestNet.setDisable(true);
		 
		 btnSSS.setPadding(new Insets(-4,0,0,0));
		 Label labelforward = AwesomeDude.createIconLabel(AwesomeIcon.CARET_RIGHT, "45");
		 labelforward.setPadding(new Insets(0,0,0,6));
		 btnContinue1.setGraphic(labelforward);
		 Label labelback = AwesomeDude.createIconLabel(AwesomeIcon.CARET_LEFT, "45");
		 labelback.setPadding(new Insets(0,6,0,0));
		 btnBack1.setGraphic(labelback);
		 Label labelforward2 = AwesomeDude.createIconLabel(AwesomeIcon.CARET_RIGHT, "45");
		 labelforward2.setPadding(new Insets(0,0,0,6));
		 btnContinue2.setGraphic(labelforward2);
		 Label labelforward3 = AwesomeDude.createIconLabel(AwesomeIcon.CARET_RIGHT, "45");
		 labelforward3.setPadding(new Insets(0,0,0,6));
		 btnContinue3.setGraphic(labelforward3);
		 Label labelback2 = AwesomeDude.createIconLabel(AwesomeIcon.CARET_LEFT, "45");
		 labelback2.setPadding(new Insets(0,6,0,0));
		 btnBack2.setGraphic(labelback2);
		 Label labelback3 = AwesomeDude.createIconLabel(AwesomeIcon.CARET_LEFT, "45");
		 labelback3.setPadding(new Insets(0,6,0,0));
		 btnBack3.setGraphic(labelback3);
		 Label labelback4 = AwesomeDude.createIconLabel(AwesomeIcon.CARET_LEFT, "45");
		 labelback4.setPadding(new Insets(0,6,0,0));
		 btnBack4.setGraphic(labelback4);
		 Label labelback5 = AwesomeDude.createIconLabel(AwesomeIcon.CARET_LEFT, "45");
		 labelback5.setPadding(new Insets(0,6,0,0));
		 btnBack5.setGraphic(labelback5);
		 Label lblMnemonic = AwesomeDude.createIconLabel(AwesomeIcon.KEYBOARD_ALT, "90");
		 btnMnemonic.setGraphic(lblMnemonic);
		 Label lblScanQR = AwesomeDude.createIconLabel(AwesomeIcon.QRCODE, "90");
		 btnScanQR.setGraphic(lblScanQR);
		 lblMinimize.setPadding(new Insets(0,20,0,0));
		 // Pane Control
		 Tooltip.install(lblMinimize, new Tooltip("Minimize Window"));
		 lblMinimize.setOnMousePressed(new EventHandler<MouseEvent>(){
			 @Override
			 public void handle(MouseEvent t) {
				 Main.startup.setIconified(true);
			 }
		 });
		 Tooltip.install(lblClose, new Tooltip("Close Window"));
		 lblClose.setOnMousePressed(new EventHandler<MouseEvent>(){
			 @Override
			 public void handle(MouseEvent t) {
				 com.sun.javafx.application.PlatformImpl.tkExit();
				 Platform.exit();
				 
			 }
		 });
		 btnContinue2.setStyle("-fx-background-color: #badb93;");
		 lblSeed.setFont(Font.font(null, FontWeight.BOLD, 18));
		 lblSeed.setPadding(new Insets(0,6,0,6));
		 txPieces.lengthProperty().addListener(new ChangeListener<Number>(){
             @Override
             public void changed(ObservableValue<? extends Number> observable, Number oldValue, Number newValue) { 
                   if(newValue.intValue() > oldValue.intValue()){
                       char ch = txPieces.getText().charAt(oldValue.intValue());  
                       //Check if the new character is the number or other's
                       if(!(ch >= '0' && ch <= '9')){       
                            //if it's not number then just setText to previous one
                            txPieces.setText(txPieces.getText().substring(0,txPieces.getText().length()-1)); 
                       }
                  }
             }
		 });
		 txThreshold.lengthProperty().addListener(new ChangeListener<Number>(){
             @Override
             public void changed(ObservableValue<? extends Number> observable, Number oldValue, Number newValue) { 
                   if(newValue.intValue() > oldValue.intValue()){
                       char ch = txThreshold.getText().charAt(oldValue.intValue());  
                       //Check if the new character is the number or other's
                       if(!(ch >= '0' && ch <= '9')){       
                            //if it's not number then just setText to previous one
                            txThreshold.setText(txThreshold.getText().substring(0,txThreshold.getText().length()-1)); 
                       }
                  }
             }
		 });
		 final ContextMenu contextMenu2 = new ContextMenu();
		 MenuItem item12 = new MenuItem("Copy");
		 item12.setOnAction(new EventHandler<ActionEvent>() {
			 public void handle(ActionEvent e) {
				 Clipboard clipboard = Clipboard.getSystemClipboard();
				 ClipboardContent content = new ClipboardContent();
				 content.putString(lvSSS.getSelectionModel().getSelectedItem().toString());
				 clipboard.setContent(content);
			 }
		 });
		 contextMenu2.getItems().addAll(item12);
		 lvSSS.setContextMenu(contextMenu2);
	 }
	 
	 @FXML protected void drag1(MouseEvent event) {
		 xOffset = event.getSceneX();
		 yOffset = event.getSceneY();
	 }

	 @FXML protected void drag2(MouseEvent event) {
		 Main.startup.setX(event.getScreenX() - xOffset);
		 Main.startup.setY(event.getScreenY() - yOffset);
	 }
	 
	 @FXML protected void restoreFromSeed(ActionEvent event) {
		 Animation ani = GuiUtils.fadeOut(MainPane);
		 GuiUtils.fadeIn(MainRestorePane);
		 MainPane.setVisible(false);
		 MainRestorePane.setVisible(true);
	 }
	 
	 @FXML protected void newWallet(ActionEvent event) throws IOException {
		 // app params
		 String filePath = new java.io.File( "." ).getCanonicalPath() + "/" + appParams.getAppName() + ".wallet";
		 File f = new File(filePath);
		 String filePath2 = new java.io.File( "." ).getCanonicalPath() + "/" + appParams.getAppName() + "Temp" + ".wallet";
		 File temp = new File(filePath2);
		 if(!f.exists()) { 
			 //Generate a new Seed
			 SecureRandom secureRandom = null;
			 try {secureRandom = SecureRandom.getInstance("SHA1PRNG");} 
			 catch (NoSuchAlgorithmException e) {e.printStackTrace();}
			 //byte[] bytes = new byte[16];
			 //secureRandom.nextBytes(bytes);
			 seed = new DeterministicSeed(secureRandom, 8 * 16, "", Utils.currentTimeSeconds());
			 
			 // set wallet
			 wallet = Wallet.fromSeed(params,seed);
			 wallet.setKeychainLookaheadSize(0);
			 wallet.autosaveToFile(f, 200, TimeUnit.MILLISECONDS, null);
			 
			 // create master public key
			 DeterministicKey masterPubKey = HDKeyDerivation.createMasterPrivateKey(seed.getSecretBytes()).getPubOnly();
			 
			 // set Authenticator wallet
			 auth = new Authenticator(masterPubKey, appParams);
			 
			 // update params in main
			 Main.returnedParamsFromSetup = appParams;
			 
			 for (String word : seed.getMnemonicCode()){mnemonic = mnemonic + word + " ";}
			 lblSeed.setText(mnemonic);
			 final ContextMenu contextMenu = new ContextMenu();
			 MenuItem item1 = new MenuItem("Copy");
			 item1.setOnAction(new EventHandler<ActionEvent>() {
				 public void handle(ActionEvent e) {
					 Clipboard clipboard = Clipboard.getSystemClipboard();
					 ClipboardContent content = new ClipboardContent();
					 content.putString(lblSeed.getText().toString());
					 clipboard.setContent(content);
				 }
			 });
			 contextMenu.getItems().addAll(item1);
			 lblSeed.setContextMenu(contextMenu);
		 }
		 Animation ani = GuiUtils.fadeOut(MainPane);
		 GuiUtils.fadeIn(CreateAccountPane);
		 MainPane.setVisible(false);
		 CreateAccountPane.setVisible(true);
	 }
	 
	 private void createNewStandardAccount(String accountName) throws IOException{
		 ATAccount acc = auth.getWalletOperation().generateNewStandardAccount(appParams.getBitcoinNetworkType(), accountName);
		 auth.getWalletOperation().setActiveAccount(acc.getIndex());
	 }
	 
	 @FXML protected void toMainPane(ActionEvent event) {
		 Animation ani = GuiUtils.fadeOut(MainRestorePane);
		 Animation ani2 = GuiUtils.fadeOut(CreateAccountPane);
		 GuiUtils.fadeIn(MainPane);
		 CreateAccountPane.setVisible(false);
		 MainRestorePane.setVisible(false);
		 MainPane.setVisible(true);
	 }
	 
	 @FXML protected void toCreateAccountPane(ActionEvent event) {
		 Animation ani = GuiUtils.fadeOut(BackupNewWalletPane);
		 GuiUtils.fadeIn(CreateAccountPane);
		 BackupNewWalletPane.setVisible(false);
		 CreateAccountPane.setVisible(true);
	 }
	 
	 @FXML protected void toExplanationPane1(ActionEvent event) {
		 if (ckSeed.isSelected()){
			 Animation ani = GuiUtils.fadeOut(BackupNewWalletPane);
			 GuiUtils.fadeIn(ExplanationPane1);
			 BackupNewWalletPane.setVisible(false);
			 ExplanationPane1.setVisible(true);
		 }
	 }
	 
	 @FXML protected void finished(ActionEvent event){
		 auth.addListener(new Service.Listener() {
				@Override public void terminated(State from) {
					auth.disposeOfAuthenticator();
					Main.startup.hide();
					Main.stage.show();
					wallet.encrypt(txPW2.getText());
					try {
						Main.finishLoading();
					} catch (IOException e) { e.printStackTrace(); }
		         }
			}, MoreExecutors.sameThreadExecutor());
         auth.stopAsync();
		 
	 }
	 
	 @FXML protected void openPlayStore(ActionEvent event) throws IOException{
		 String url = "https://play.google.com/";
         java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
	 }
	 
	 @FXML protected void baNext(ActionEvent event){
		 if (BA1.isVisible()){
			 Animation ani = GuiUtils.fadeOut(BA1);
			 GuiUtils.fadeIn(BA2);
			 BA1.setVisible(false);
			 BA2.setVisible(true);
		 } else if (BA2.isVisible()){
			 Animation ani = GuiUtils.fadeOut(BA2);
			 GuiUtils.fadeIn(BA3);
			 BA2.setVisible(false);
			 BA3.setVisible(true);
		 } else if (BA3.isVisible()){
			 Animation ani = GuiUtils.fadeOut(BA3);
			 GuiUtils.fadeIn(BA4);
			 BA3.setVisible(false);
			 BA4.setVisible(true);
			 Animation ani2 = GuiUtils.fadeOut(btnContinue3);
			 GuiUtils.fadeIn(hlFinished);
			 btnContinue3.setVisible(false);
			 hlFinished.setVisible(true);
		 } 
	 }
	 
	 @FXML protected void backToBackupNewWalletPane(ActionEvent event){
		 Animation ani = GuiUtils.fadeOut(ExplanationPane1);
		 GuiUtils.fadeIn(BackupNewWalletPane);
		 ExplanationPane1.setVisible(false);
		 BackupNewWalletPane.setVisible(true);
	 }
	 
	 @FXML protected void toBackupNewWalletPane(ActionEvent event) {
		 if (txAccount.getText().toString().equals("")){
			 informationalAlert("Unfortunately, you messed up.",
					 "You need to enter a name for your account");
		 }
		 else if (!txPW1.getText().toString().equals(txPW2.getText().toString())){
			 informationalAlert("Unfortunately, you messed up.",
					 "Your passwords don't match");
		 }
		 else if (txPW1.getText().toString().equals("") && txPW2.getText().toString().equals("")){
			 informationalAlert("Unfortunately, you messed up.",
					 "You need to enter a password");
		 }
		 else {
			 try {
				createNewStandardAccount(txAccount.getText());
				
				ckSeed.selectedProperty().addListener(new ChangeListener<Boolean>() {
					 public void changed(ObservableValue<? extends Boolean> ov,
							 Boolean old_val, Boolean new_val) {
						 if (ckSeed.isSelected()){
							 btnContinue2.setStyle("-fx-background-color: #95d946;");
						 }
						 else {
							 btnContinue2.setStyle("-fx-background-color: #badb93;");
						 }
					 }
				 });
				 Animation ani = GuiUtils.fadeOut(CreateAccountPane);
				 GuiUtils.fadeIn(BackupNewWalletPane);
				 CreateAccountPane.setVisible(false);
				 BackupNewWalletPane.setVisible(true);
				 //Main.bitcoin.wallet().encrypt(txPW1.getText().toString());
			} catch (Exception e) { 
				e.printStackTrace();
				
				Dialogs.create()
		        .owner(Main.startup)
		        .title("Error")
		        .masthead("Cannot Create account !")
		        .message("Please try again")
		        .showError();
			}
			 
		 }
	 }
	 
	 @FXML protected void saveWallet(ActionEvent event) throws IOException{
		 String filepath = new java.io.File( "." ).getCanonicalPath() + "/" + appParams.getAppName() + ".wallet";
		 File wallet = new File(filepath);
		 FileChooser fileChooser = new FileChooser();
		 fileChooser.setTitle("Save Wallet");
		 fileChooser.setInitialFileName(appParams.getAppName() + ".wallet");
		 File file = fileChooser.showSaveDialog(Main.startup);
		 FileChannel source = null;
		 FileChannel destination = null;
		 if (file != null) {
			 try {
				 source = new FileInputStream(wallet).getChannel();
				 destination = new FileOutputStream(file).getChannel();
				 destination.transferFrom(source, 0, source.size());
			 }	
			 finally {
				 if(source != null) {
					 source.close();
				 }
				 if(destination != null) {
					 destination.close();
				 }
			 }	 
		 }
	 }
	 
	 @FXML protected void printPaperWallet(ActionEvent event) throws IOException{
		 PaperWallet.createPaperWallet(mnemonic, seed);
	 }
	 
	 @FXML protected void openSSS(ActionEvent event){
		 Animation ani = GuiUtils.fadeOut(BackupNewWalletPane);
		 GuiUtils.fadeIn(SSSBackupPane);
		 BackupNewWalletPane.setVisible(false);
		 SSSBackupPane.setVisible(true); 
	 }
	 
	 @FXML protected void split(ActionEvent event) throws IOException{
		 BipSSS sss = new BipSSS();
		 List<Share> shares = sss.shard(EncodingUtils.hexStringToByteArray(seed.toHexString()), 
				 Integer.parseInt(txThreshold.getText().toString()), Integer.parseInt(txPieces.getText().toString()), EncodingFormat.SHORT, params);
		 final ObservableList list = FXCollections.observableArrayList();
		 for (Share share: shares){list.add(share.toString());}
		 lvSSS.setItems(list);
		 
	 }
	 
	 @FXML protected void returntoBackupNewWalletPane(ActionEvent event){
		 Animation ani = GuiUtils.fadeOut(SSSBackupPane);
		 GuiUtils.fadeIn(BackupNewWalletPane);
		 SSSBackupPane.setVisible(false);
		 BackupNewWalletPane.setVisible(true);
	 }
	 
	 @FXML protected void openWeb(ActionEvent event){
		 Animation ani = GuiUtils.fadeOut(CreateAccountPane);
		 GuiUtils.fadeIn(Pane6);
		 CreateAccountPane.setVisible(false);
		 Pane6.setVisible(true);
		 browser.autosize();
		 URL location = Main.class.getResource("passwords.html");
		 browser.getEngine().load(location.toString());
	 }
	 
	 @FXML protected void webFinished(ActionEvent event){
		 Animation ani = GuiUtils.fadeOut(Pane6);
		 GuiUtils.fadeIn(CreateAccountPane);
		 Pane6.setVisible(false);
		 CreateAccountPane.setVisible(true);
	 }
	 
	 @FXML protected void btnBackPressed(MouseEvent event) {
		 btnBack1.setStyle("-fx-background-color: #d7d4d4;");
		 btnBack2.setStyle("-fx-background-color: #d7d4d4;");
		 btnBack3.setStyle("-fx-background-color: #d7d4d4;");
		 btnBack4.setStyle("-fx-background-color: #d7d4d4;");
		 btnBack5.setStyle("-fx-background-color: #d7d4d4;");
	 }
	    
	 @FXML protected void btnBackReleased(MouseEvent event) {
		 btnBack1.setStyle("-fx-background-color: #808080;");
		 btnBack2.setStyle("-fx-background-color: #808080;");
		 btnBack3.setStyle("-fx-background-color: #808080;");
		 btnBack4.setStyle("-fx-background-color: #808080;");
		 btnBack5.setStyle("-fx-background-color: #808080;");
	 }
	    
	 @FXML protected void btnContinuePressed(MouseEvent event) {
		 btnContinue1.setStyle("-fx-background-color: #badb93;");
		 if (ckSeed.isSelected()){btnContinue2.setStyle("-fx-background-color: #badb93;");}
		 btnContinue3.setStyle("-fx-background-color: #badb93;");
		 btnDone.setStyle("-fx-background-color: #badb93; -fx-text-fill: white;");
	 }
	    
	 @FXML protected void btnContinueReleased(MouseEvent event) {
		 btnContinue1.setStyle("-fx-background-color: #95d946;");
		 if (ckSeed.isSelected()){btnContinue2.setStyle("-fx-background-color: #95d946;");}
		 btnContinue3.setStyle("-fx-background-color: #95d946;");
		 btnDone.setStyle("-fx-background-color: #95d946; -fx-text-fill: white;");
	 } 
	 

}
