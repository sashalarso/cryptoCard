package client;

import java.util.*;
import java.io.*;
import opencard.core.service.*;
import opencard.core.terminal.*;
import opencard.core.util.*;
import opencard.opt.util.*;

public class TheClient {

	private PassThruCardService servClient = null;

	/* APDU */
	private static short DMS = 127; // DATA MAX SIZE
	private static short DMS_DES = 248; // DATA MAX SIZE for DES
	private static final byte CLA = (byte) 0x37;
	private static final byte P1 = (byte) 0x00;
	private static final byte P2 = (byte) 0x00;

	/* INSTRUCTION CODES */
	private static final byte RETRIEVEFILEBYID = (byte) 0x06;
	private static final byte LISTFILES = (byte) 0x05;
	private static final byte ADDFILE = (byte) 0x04;
	private static final byte DECRYPTFILE = (byte) 0x02;
	private static final byte ENCRYPTFILE = (byte) 0x01;
	private static final byte CHANGEDES = (byte) 0x07;

	/* RESPONSE STATUS */

	/* P1 CODES */
	private static final byte FILEINFO = (byte) 0xcc;
	private static final byte FULLAPDU = (byte) 0xca;
	private static final byte LASTAPDU = (byte) 0xfe;
	private static final byte GETFILES = (byte) 0xef;

	/* P2 CODES */

	/* BOOLEANS */
	private boolean DISPLAY = true;
	private boolean loop = true;

	public TheClient() {
		try {
			SmartCard.start();
			System.out.print("Smartcard inserted?... ");

			CardRequest cr = new CardRequest(CardRequest.ANYCARD, null, null);

			SmartCard sm = SmartCard.waitForCard(cr);

			if (sm != null) {
				System.out.println("got a SmartCard object!\n");
			} else
				System.out.println("did not get a SmartCard object!\n");

			this.initNewCard(sm);

			SmartCard.shutdown();

		} catch (Exception e) {
			System.out.println("TheClient error: " + e.getMessage());
		}
		java.lang.System.exit(0);
	}

	private ResponseAPDU sendAPDU(CommandAPDU cmd) {
		return sendAPDU(cmd, true);
	}

	private ResponseAPDU sendAPDU(CommandAPDU cmd, boolean display) {
		ResponseAPDU result = null;
		try {
			result = this.servClient.sendCommandAPDU(cmd);
			if (display)
				displayAPDU(cmd, result);
		} catch (Exception e) {
			System.out.println("Exception caught in sendAPDU: " + e.getMessage());
			java.lang.System.exit(-1);
		}
		return result;
	}

	/************************************************
	 ************ BEGINNING OF TOOLS ****************
	 ************************************************/

	private String apdu2string(APDU apdu) {
		return removeCR(HexString.hexify(apdu.getBytes()));
	}

	public void displayAPDU(APDU apdu) {
		System.out.println(removeCR(HexString.hexify(apdu.getBytes())) + "\n");
	}

	public void displayAPDU(CommandAPDU termCmd, ResponseAPDU cardResp) {
		System.out.println("--> Term: " + removeCR(HexString.hexify(termCmd.getBytes())));
		System.out.println("<-- Card: " + removeCR(HexString.hexify(cardResp.getBytes())));
	}

	private String removeCR(String string) {
		return string.replace('\n', ' ');
	}

	/******************************************
	 ************ END OF TOOLS ****************
	 ******************************************/

	private boolean selectApplet() {
		boolean cardOk = false;
		try {
			CommandAPDU cmd = new CommandAPDU(new byte[] {
					(byte) 0x00, (byte) 0xA4, (byte) 0x04, (byte) 0x00, (byte) 0x0A,
					(byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x62,
					(byte) 0x03, (byte) 0x01, (byte) 0x0C, (byte) 0x06, (byte) 0x01
			});
			ResponseAPDU resp = this.sendAPDU(cmd);
			if (this.apdu2string(resp).equals("90 00"))
				cardOk = true;
		} catch (Exception e) {
			System.out.println("Exception caught in selectApplet: " + e.getMessage());
			java.lang.System.exit(-1);
		}
		return cardOk;
	}

	private void initNewCard(SmartCard card) {
		if (card != null)
			System.out.println("Smartcard inserted\n");
		else {
			System.out.println("Did not get a smartcard");
			System.exit(-1);
		}

		System.out.println("ATR: " + HexString.hexify(card.getCardID().getATR()) + "\n");

		try {
			this.servClient = (PassThruCardService) card.getCardService(PassThruCardService.class, true);
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}

		System.out.println("Applet selecting...");
		if (!this.selectApplet()) {
			System.out.println("Wrong card, no applet to select!\n");
			System.exit(1);
			return;
		} else
			System.out.println("Applet selected\n");

		mainLoop();
	}

	/************************************************
	 ************ BEGINNING OF METHODS **************
	 *************************************************/

	private String readKeyboard() {
		String result = null;

		try {
			BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
			result = input.readLine();
		} catch (Exception e) {
		}
		return result;
	}

	private int readMenuChoice() {
		int result = -1;

		try {
			String choice = readKeyboard();
			result = Integer.parseInt(choice.trim());
		} catch (Exception e) {
		}
		System.out.println("");
		return result;
	}

		private static short byteToShort(byte b) {
		return (short) (b & 0xff);
	}

	private static short byteArrayToShort(byte[] ba, short offset) {
		return (short) (((ba[offset] << 8)) | ((ba[(short) (offset + 1)] & 0xff)));
	}

	private static byte[] shortToByteArray(short s) {
		return new byte[] { (byte) ((s & (short) 0xff00) >> 8), (byte) (s & (short) 0x00ff) };
	}

	private static byte[] addPadding(byte[] data, long fileLength) {
		short paddingSize = (short) (8 - (fileLength % 8));
		byte[] paddingData = new byte[(short) (data.length + paddingSize)];

		System.arraycopy(data, 0, paddingData, 0, (short) data.length);
		for (short i = (short) data.length; i < (data.length + paddingSize); ++i)
			paddingData[i] = shortToByteArray(paddingSize)[1];

		return paddingData;
	}

	private static byte[] removePadding(byte[] paddingData) {
		short paddingSize = byteToShort(paddingData[paddingData.length - 1]);
		if (paddingSize > 8)
			return paddingData;

		/* check if padding exists */
		for (short i = (short) (paddingData.length - paddingSize); i < paddingData.length; ++i)
			if (paddingData[i] != (byte) paddingSize)
				return paddingData;

		/* Remove padding */
		short dataLength = (short) (paddingData.length - paddingSize);
		byte[] data = new byte[dataLength];
		System.arraycopy(paddingData, 0, data, 0, (short) dataLength);

		return data;
	}

	/******************************************
	 ************ END OF METHODS **************
	 *******************************************/

	private void getFile() {
		System.out.println("Entrez l'id du fichier a retrouver\n");

		CommandAPDU cmd;
		ResponseAPDU resp;

		short fileID = Short.valueOf(readKeyboard().trim());

		byte[] payload = new byte[5];
		payload[0] = CLA;
		payload[1] = RETRIEVEFILEBYID;
		payload[2] = FILEINFO;
		payload[3] = (byte) fileID;
		payload[4] = (byte) 0;

		cmd = new CommandAPDU(payload);
		displayAPDU(cmd);
		resp = this.sendAPDU(cmd, DISPLAY);

		

		byte[] bytes = resp.getBytes();
		short nbTrunks = byteToShort(bytes[0]);
		short lastTrunkLength = byteToShort(bytes[1]);
		String filename = "";

		for (short i = 2; i < bytes.length - 2; ++i)
			filename += new StringBuffer("").append((char) bytes[i]);

		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ByteArrayOutputStream stream = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(baos);
			FileOutputStream fout = new FileOutputStream(fileID + "-" + filename);

			String msg = "(DATA TRUNK)";
			for (short i = 0; i <= nbTrunks; ++i) {
				if (i < nbTrunks) { // Trunks
					payload = new byte[7];
					payload[0] = CLA;
					payload[1] = RETRIEVEFILEBYID;
					payload[2] = FULLAPDU;
					payload[3] = (byte) fileID;
					payload[4] = (byte) 1;
					payload[5] = (byte) i;
					payload[payload.length - 1] = (byte) DMS;

				} else if (i == nbTrunks) { // Last Trunk
					payload = new byte[5];
					payload[0] = CLA;
					payload[1] = RETRIEVEFILEBYID;
					payload[2] = LASTAPDU;
					payload[3] = (byte) fileID;
					payload[4] = (byte) 0;
					msg = "(LAST DATA TRUNK)";
				}

				cmd = new CommandAPDU(payload);
				resp = this.sendAPDU(cmd, DISPLAY);

				
				bytes = resp.getBytes();

				byte[] data = new byte[bytes.length - 2];
				System.arraycopy(bytes, 0, data, 0, bytes.length - 2);

				stream.write(data);
				
			}
			dos.write(stream.toByteArray());

			try {
				baos.writeTo(fout);
				stream.flush();
				fout.flush();
			} finally {
				fout.close();
				System.out.println(" ");
				System.out.println("Sortie : " + fileID + "-" + filename);
				System.out.println(" ");
				
			}
		} catch (IOException oe) {
			System.out.println("[IOException] " + oe.getMessage());
		}
	}
	

	private void listFiles() {
		System.out.println("LISTFILES\n");

		CommandAPDU cmd;
		ResponseAPDU resp;

		byte[] payload = new byte[5];
		payload[0] = CLA;
		payload[1] = LISTFILES;
		payload[2] = FILEINFO;
		payload[3] = P2;
		payload[4] = (byte) 0;

		cmd = new CommandAPDU(payload);
		resp = this.sendAPDU(cmd, DISPLAY);

		
		byte[] bytes = resp.getBytes();
		short nbFiles = (short) bytes[0];

		if (nbFiles < 1) {
			System.out.println("------------------------------");
			System.out.println("> 0 fichiers stockes dans la carte.");
			System.out.println("------------------------------");
		} else {
			List<Short> fileIDList = new ArrayList<Short>();
			List<Short> fileLengthList = new ArrayList<Short>();
			for (short i = 0, j = 1; i < nbFiles; ++i, j += 2) {
				fileLengthList.add(byteArrayToShort(bytes, j));
				fileIDList.add(i);
			}

			List<String> fileList = new ArrayList<String>();
			for (short i = 0; i < nbFiles; ++i) {
				payload = new byte[5];
				payload[0] = CLA;
				payload[1] = LISTFILES;
				payload[2] = GETFILES;
				payload[3] = (byte) i;
				payload[4] = (byte) 0;

				cmd = new CommandAPDU(payload);
				displayAPDU(cmd);
				resp = this.sendAPDU(cmd, DISPLAY);

				
				String filename = "";
				for (short j = 0; j < resp.getBytes().length - 2; j++)
					filename += new StringBuffer("").append((char) resp.getBytes()[j]);
				fileList.add(filename);
				
			}

			Short[] fileIDArray = new Short[fileIDList.size()];
			fileIDArray = fileIDList.toArray(fileIDArray);

			Short[] fileLengthArray = new Short[fileLengthList.size()];
			fileLengthArray = fileLengthList.toArray(fileLengthArray);

			String[] fileArray = new String[fileList.size()];
			fileArray = fileList.toArray(fileArray);

			for (short i = 1; i < fileLengthArray.length; ++i) {
				boolean flag = true;

				for (short j = 0; j < fileLengthArray.length - i; ++j) {
					if (fileLengthArray[j] > fileLengthArray[j + 1]) {
						short tmp = fileLengthArray[j];
						fileLengthArray[j] = fileLengthArray[j + 1];
						fileLengthArray[j + 1] = tmp;

						tmp = fileIDArray[j];
						fileIDArray[j] = fileIDArray[j + 1];
						fileIDArray[j + 1] = tmp;

						String tmpS = fileArray[j];
						fileArray[j] = fileArray[j + 1];
						fileArray[j + 1] = tmpS;

						flag = false;
					}
				}
				if (flag)
					break;
			}

			fileLengthList = Arrays.asList(fileLengthArray);
			fileList = Arrays.asList(fileArray);
			fileIDList = Arrays.asList(fileIDArray);

			System.out.println(" ");
			System.out.println(
					nbFiles > 1 ? ("> " + nbFiles + " fichiers dans la carte: [ID]")
							: ("> " + nbFiles + " fichier dans la carte: [ID]"));
			for (short i = 0; i < fileList.size(); ++i)
				System.out.println(" [" + fileIDList.get(i) + "] " + fileList.get(i) + " ("
						+ (fileLengthList.get(i) > 1 ? (fileLengthList.get(i) + " bytes)")
								: (fileLengthList.get(i) + " byte)")));
			System.out.println(" ");
		}
	}
	
	
	private void addFile() {
		CommandAPDU cmd;
		ResponseAPDU resp;

		System.out.println("Entrez le nom du fichier a stocker");
		String filename = readKeyboard();
		File f = new File(filename.trim());
		
		System.out.println("");
		
		if (!f.exists()) {
			System.err.println("ERREUR : le fichier n'existe pas");
			return;
		}

		/* metadata */
		byte filenameLength = (byte) filename.length(); // 1 byte
		byte[] filename_b = filename.getBytes(); // n bytes

		if (filenameLength < 0) {
			System.err.println("[Error]: Filename is too large: " + filenameLength
					+ " characters (length must be <= 127 characters)");
			return;
		}

		if (f.length() < 0 || f.length() > 32767) {
			System.err.println("[Error]: File is too large: " + f.length() + " bytes (size must be <= 32767 bytes)");
			return;
		}

		short fileLength = (short) f.length(); // 2 bytes
		short LC = (short) (filenameLength + 3);
		byte[] payload = new byte[LC + 5];
		payload[0] = CLA;
		payload[1] = ADDFILE;
		payload[2] = FILEINFO;
		payload[3] = P2;
		payload[4] = (byte) LC;
		payload[5] = filenameLength; // filename length
		System.arraycopy(filename_b, 0, payload, 6, filenameLength); // filename
		payload[payload.length - 2] = shortToByteArray(fileLength)[0]; 
		payload[payload.length - 1] = shortToByteArray(fileLength)[1]; 

		cmd = new CommandAPDU(payload);
		resp = this.sendAPDU(cmd, DISPLAY);

		try {
			FileInputStream fin = new FileInputStream(f);
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			int read_from_stream = 0, i = 0, cpt = 0;

			while (read_from_stream != -1) {
				read_from_stream = fin.read();
				outputStream.write(read_from_stream);
				++i;

				if (read_from_stream != -1 && i == DMS) {
					byte data[] = outputStream.toByteArray();
					outputStream = new ByteArrayOutputStream();
					i = 0;
					//System.out.print("\n" + "Trunk #" + cpt++ + " [length: " + data.length + "]");
					//System.out.println("");

					LC = (short) data.length;
					payload = new byte[LC + 5];
					payload[0] = CLA;
					payload[1] = ADDFILE;
					payload[2] = FULLAPDU;
					payload[3] = P2;
					payload[4] = (byte) LC;
					System.arraycopy(data, 0, payload, 5, LC);

					cmd = new CommandAPDU(payload);
					resp = this.sendAPDU(cmd, DISPLAY);

					

				} else if (read_from_stream == -1 && i > 1) {
					byte data[] = outputStream.toByteArray();
					outputStream = new ByteArrayOutputStream();
					//System.out.print("\n" + "Trunk #" + cpt++ + " [length: " + (data.length - 1) + "]\n");

					LC = (short) (data.length - 1);
					payload = new byte[LC + 5];
					payload[0] = CLA;
					payload[1] = ADDFILE;
					payload[2] = LASTAPDU;
					payload[3] = P2;
					payload[4] = (byte) LC;
					System.arraycopy(data, 0, payload, 5, LC);

					cmd = new CommandAPDU(payload);
					resp = this.sendAPDU(cmd, DISPLAY);

					
				}
			}
			fin.close();
			System.out.println("");
			System.out.println("Ajoute dans la carte: " + filename + "\n");
			System.out.println("");
		} catch (IOException oe) {
			System.err.println("[IOException exception] " + oe.getMessage());
		}
		}
	

	private void changeDESkey(){
		CommandAPDU cmd;
		ResponseAPDU resp;

		System.out.println("Entrez la nouvelle cle DES (8 octets)");
		String new_key = readKeyboard();
		if(new_key.length()!=16 ){
			System.err.println("La clé doit etre de taille 8 octets");
			return;
		}
				
		byte[] payload = new byte[5+8];
		payload[0]=CLA;
		payload[1]=CHANGEDES;
		payload[2]=P1;
		payload[3]=P2;
		payload[4]= (byte) 8;	
		
		System.arraycopy(new_key.getBytes(), 0, payload,5, 8);

		cmd = new CommandAPDU(payload);
		resp = this.sendAPDU(cmd, DISPLAY);

		System.out.println("");
		System.out.println("Cle DES modifiee !");
		System.out.println("");

	}

	private void decryptFile() {
		CommandAPDU cmd;
		ResponseAPDU resp;

		System.out.println("Entrez le nom du fichier à déchiffrer");
		String filename = readKeyboard();
		File f = new File(filename.trim());
		System.out.println("");

		if (!f.exists()) {
			System.err.println("ERREUR : le fichier n'existe pas");
			return;
		}

		System.out.println("Entrez le nom du fichier de sortie");
		String output = readKeyboard();

		try {
			FileInputStream fin = new FileInputStream(f);
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

			ByteArrayOutputStream stream = new ByteArrayOutputStream(); // sortir
			FileOutputStream fout = new FileOutputStream(output);
			int read_from_stream = 0, i = 0, cpt = 1;

			while (read_from_stream != -1) {
				read_from_stream = fin.read();
				if (read_from_stream != -1)
					outputStream.write(read_from_stream);
				++i;

				if ((read_from_stream != -1 && i == DMS_DES) || (read_from_stream == -1 && i > 1)) {
					byte data[] = outputStream.toByteArray();
					outputStream = new ByteArrayOutputStream();
					i = 0;

					//System.out.print("\n" + "Trunk #" + cpt + " [length: " + data.length + "]");
					//System.out.println("");

					byte[] payload = new byte[data.length + 6];
					payload[0] = CLA;
					payload[1] = DECRYPTFILE;
					payload[2] = P1;
					payload[3] = P2;
					payload[4] = (byte) data.length;
					System.arraycopy(data, 0, payload, 5, data.length);
					payload[payload.length - 1] = (byte) data.length;

					cmd = new CommandAPDU(payload);
					resp = this.sendAPDU(cmd, DISPLAY);					

					byte[] bytes = resp.getBytes();
					try {
						DataOutputStream dos = new DataOutputStream(stream);
						data = new byte[bytes.length - 2];
						System.arraycopy(bytes, 0, data, 0, bytes.length - 2);
						if (cpt++ * DMS_DES >= f.length())
							data = removePadding(data);
						dos.write(data);

					} catch (IOException oe) {
						System.out.println("[IOException] " + oe.getMessage());
					}
					
				}
			}
			try {
				stream.writeTo(fout);
				stream.flush();
				fout.flush();
			} finally {
				fout.close();
			}
			fin.close();
			System.out.println(" ");
			System.out.println("Dechiffrement reussi !");
			System.out.println(" ");
		} catch (IOException oe) {
			System.err.println("[IOException exception] " + oe.getMessage());
		}
	}

	private void encryptFile() {
		CommandAPDU cmd;
		ResponseAPDU resp;

		System.out.println("Entrez le nom du fichier a chiffrer.");
		String filename = readKeyboard();
		File f = new File(filename.trim());
		System.out.println("");

		if (!f.exists()) {
			System.err.println("ERREUR : le fichier n'existe pas");
			return;
		}

		System.out.println("ENCRYPTFILE: Entrez le nom du fichier de sortie.");
		String output = readKeyboard();
		
		try {
			FileInputStream fin = new FileInputStream(f);
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

			ByteArrayOutputStream stream = new ByteArrayOutputStream(); // sortir
			FileOutputStream fout = new FileOutputStream(output);
			int read_from_stream = 0, i = 0, cpt = 0;

			while (read_from_stream != -1) {
				read_from_stream = fin.read();
				if (read_from_stream != -1)
					outputStream.write(read_from_stream);
				++i;
				if ((read_from_stream != -1 && i == DMS_DES) || (read_from_stream == -1 && i > 1)) {
					byte data[] = outputStream.toByteArray();
					outputStream = new ByteArrayOutputStream();
					i = 0;

					if (read_from_stream == -1)
						data = addPadding(data, f.length());

					//System.out.print("\n" + "Trunk #" + cpt++ + " [length: " + data.length + "]");
					//System.out.println("");

					byte[] payload = new byte[data.length + 6];
					payload[0] = CLA;
					payload[1] = ENCRYPTFILE;
					payload[2] = P1;
					payload[3] = P2;
					payload[4] = (byte) data.length;
					System.arraycopy(data, 0, payload, 5, data.length);
					payload[payload.length - 1] = (byte) data.length;

					cmd = new CommandAPDU(payload);
					displayAPDU(cmd);
					resp = this.sendAPDU(cmd, DISPLAY);					

					byte[] bytes = resp.getBytes();
					try {
						DataOutputStream dos = new DataOutputStream(stream);
						data = new byte[bytes.length - 2];
						System.arraycopy(bytes, 0, data, 0, bytes.length - 2);
						dos.write(data);

					} catch (IOException oe) {
						System.out.println("[IOException] " + oe.getMessage());
					}
					
				}
			}
			try {
				stream.writeTo(fout);
				stream.flush();
				fout.flush();
			} finally {
				fout.close();
			}
			fin.close();
			System.out.println(" ");
			System.out.println("Chiffrement reussi !");
			System.out.println(" ");
		} catch (IOException oe) {
			System.err.println("[IOException exception] " + oe.getMessage());
		}
	}

	private void exit() {
		System.out.println("Au revoir :)");
		loop = false;
	}

	private void runAction(int choice) {
		switch (choice) {
			case 6:
				changeDESkey();
				break;
			case 5:
				decryptFile();
				break;
			case 4:
				encryptFile();
				break;
			case 3:
				getFile();
				break;
			case 2:
				listFiles();
				break;
			case 1:
				addFile();
				break;
			case 0:
				exit();
				break;
			default:
				System.out.println("Unknown choice!");
		}
	}

	private void printMenu() {
		
		System.out.println("  Menu Cryptocard  \n");
		
		System.out.println("6: Changer la clef DES de la carte");
		System.out.println("5: Entrer le nom d'un fichier a dechiffrer + nom du fichier de sortie  (DES)");
		System.out.println("4: Entrer le nom d'un fichier a chiffrer + nom du fichier de sortie (DES)");
		System.out.println("3: Recuperer un fichier depuis la carte (entrer son numero)");
		System.out.println("2: Lister les fichiers contenus dans la carte (numero, nom et taille pour chaque)");
		System.out.println("1: Entrer le nom du fichier a ajouter dans la carte");
		System.out.println("0: Quitter");
		System.out.print("--> ");
	}

	private void mainLoop() {
		while (loop) {
			printMenu();
			int choice = readMenuChoice();
			runAction(choice);
		}
	}

	public static void main(String[] args) throws InterruptedException {
		new TheClient();
	}
}
