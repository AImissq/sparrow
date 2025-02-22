package com.sparrowwallet.sparrow.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sparrowwallet.drongo.OutputDescriptor;
import com.sparrowwallet.drongo.wallet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SpecterDesktop implements WalletImport, WalletExport {
    private static final Logger log = LoggerFactory.getLogger(SpecterDesktop.class);

    @Override
    public void exportWallet(Wallet wallet, OutputStream outputStream) throws ExportException {
        try {
            SpecterWallet specterWallet = new SpecterWallet();
            specterWallet.label = wallet.getFullName();
            specterWallet.blockheight = wallet.getTransactions().values().stream().mapToInt(BlockTransactionHash::getHeight).min().orElse(wallet.getStoredBlockHeight());
            specterWallet.descriptor = OutputDescriptor.getOutputDescriptor(wallet).toString(true);

            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            String json = gson.toJson(specterWallet);
            outputStream.write(json.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch(Exception e) {
            log.error("Error exporting " + getName() + " wallet", e);
            throw new ExportException("Error exporting " + getName() + " wallet", e);
        }
    }

    @Override
    public String getWalletExportDescription() {
        return "Export a Specter wallet that can be read by Specter Desktop using Add new wallet > Import from wallet software";
    }

    @Override
    public String getExportFileExtension(Wallet wallet) {
        return "json";
    }

    @Override
    public String getWalletImportDescription() {
        return "Import a Specter wallet created by using the Wallets > Settings > Export to Wallet Software in Specter Desktop. Note Connected (USB) Hardware Wallets may need to be reimported separately as the export file may not contain this information.";
    }

    @Override
    public Wallet importWallet(InputStream inputStream, String password) throws ImportException {
        try {
            Gson gson = new Gson();
            SpecterWallet specterWallet = gson.fromJson(new InputStreamReader(inputStream, StandardCharsets.UTF_8), SpecterWallet.class);

            if(specterWallet.descriptor != null) {
                OutputDescriptor outputDescriptor = OutputDescriptor.getOutputDescriptor(specterWallet.descriptor);
                Wallet wallet = outputDescriptor.toWallet();
                wallet.setName(specterWallet.label);

                if(specterWallet.devices != null && specterWallet.devices.size() == wallet.getKeystores().size()) {
                    boolean uniqueLabels = specterWallet.devices.stream().map(SpecterWalletDevice::getLabel).distinct().count() == specterWallet.devices.size();
                    for(int i = 0; i < specterWallet.devices.size(); i++) {
                        SpecterWalletDevice device = specterWallet.devices.get(i);
                        Keystore keystore = wallet.getKeystores().get(i);
                        keystore.setLabel(device.getLabel() + (uniqueLabels ? "" : " " + i));

                        WalletModel walletModel = device.getWalletModel();
                        if(walletModel != null) {
                            keystore.setWalletModel(walletModel);
                            if(walletModel == WalletModel.TREZOR_1 || walletModel == WalletModel.TREZOR_T || walletModel == WalletModel.KEEPKEY ||
                                walletModel == WalletModel.LEDGER_NANO_S || walletModel == WalletModel.LEDGER_NANO_X ||
                                walletModel == WalletModel.BITBOX_02 || walletModel == WalletModel.COLDCARD) {
                                keystore.setSource(KeystoreSource.HW_USB);
                            } else if(walletModel == WalletModel.BITCOIN_CORE) {
                                keystore.setSource(KeystoreSource.SW_WATCH);
                            } else {
                                keystore.setSource(KeystoreSource.HW_AIRGAPPED);
                            }
                        }
                    }
                }

                try {
                    wallet.checkWallet();
                } catch(InvalidWalletException e) {
                    throw new ImportException("Imported " + getName() + " wallet was invalid: " + e.getMessage());
                }

                return wallet;
            }
        } catch(Exception e) {
            throw new ImportException("Error importing " + getName() + " wallet", e);
        }

        throw new ImportException("File was not a valid " + getName() + " wallet");
    }

    @Override
    public boolean isEncrypted(File file) {
        return false;
    }

    @Override
    public boolean isWalletImportScannable() {
        return true;
    }

    @Override
    public boolean isWalletExportScannable() {
        return true;
    }

    @Override
    public String getName() {
        return "Specter Desktop";
    }

    @Override
    public WalletModel getWalletModel() {
        return WalletModel.SPECTER_DESKTOP;
    }

    @Override
    public boolean walletExportRequiresDecryption() {
        return false;
    }

    public static class SpecterWallet {
        public String label;
        public Integer blockheight;
        public String descriptor;
        public List<SpecterWalletDevice> devices;
    }

    public static class SpecterWalletDevice {
        public String type;
        public String label;

        public WalletModel getWalletModel() {
            if(type != null) {
                String model = type;
                if(model.equals("cobo")) {
                    model = "cobovault";
                }

                WalletModel walletModel = WalletModel.fromType(model);
                if(walletModel == WalletModel.SPECTER_DESKTOP) {
                    walletModel = WalletModel.SPECTER_DIY;
                }
                if(walletModel == WalletModel.TREZOR_1) {
                    walletModel = WalletModel.TREZOR_T;
                }

                return walletModel;
            }

            return null;
        }

        public String getLabel() {
            if(label == null) {
                label = "Keystore";
            }

            return label.length() > Keystore.MAX_LABEL_LENGTH - 3 ? label.substring(0, Keystore.MAX_LABEL_LENGTH - 3) : label;
        }
    }
}
