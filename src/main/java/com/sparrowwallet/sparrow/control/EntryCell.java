package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionInput;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.net.MempoolRateSize;
import com.sparrowwallet.sparrow.wallet.*;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import org.controlsfx.glyphfont.FontAwesome;
import org.controlsfx.glyphfont.Glyph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class EntryCell extends TreeTableCell<Entry, Entry> {
    private static final Logger log = LoggerFactory.getLogger(EntryCell.class);

    public static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private static final Pattern REPLACED_BY_FEE_SUFFIX = Pattern.compile("(.*)\\(Replaced By Fee( #)?(\\d+)?\\).*");

    public EntryCell() {
        super();
        setAlignment(Pos.CENTER_LEFT);
        setContentDisplay(ContentDisplay.RIGHT);
        getStyleClass().add("entry-cell");
    }

    @Override
    protected void updateItem(Entry entry, boolean empty) {
        super.updateItem(entry, empty);

        applyRowStyles(this, entry);

        if(empty) {
            setText(null);
            setGraphic(null);
        } else {
            if(entry instanceof TransactionEntry) {
                TransactionEntry transactionEntry = (TransactionEntry)entry;
                if(transactionEntry.getBlockTransaction().getHeight() == -1) {
                    setText("Unconfirmed Parent");
                    setContextMenu(new UnconfirmedTransactionContextMenu(transactionEntry));
                } else if(transactionEntry.getBlockTransaction().getHeight() == 0) {
                    setText("Unconfirmed");
                    setContextMenu(new UnconfirmedTransactionContextMenu(transactionEntry));
                } else {
                    String date = DATE_FORMAT.format(transactionEntry.getBlockTransaction().getDate());
                    setText(date);
                    setContextMenu(new TransactionContextMenu(date, transactionEntry.getBlockTransaction()));
                }

                Tooltip tooltip = new Tooltip();
                tooltip.setText(getTooltip(transactionEntry));
                setTooltip(tooltip);

                if(transactionEntry.getBlockTransaction().getHeight() <= 0) {
                    tooltip.setOnShowing(event -> {
                        tooltip.setText(getTooltip(transactionEntry));
                    });
                }

                HBox actionBox = new HBox();
                actionBox.getStyleClass().add("cell-actions");
                Button viewTransactionButton = new Button("");
                viewTransactionButton.setGraphic(getViewTransactionGlyph());
                viewTransactionButton.setOnAction(event -> {
                    EventManager.get().post(new ViewTransactionEvent(this.getScene().getWindow(), transactionEntry.getBlockTransaction()));
                });
                actionBox.getChildren().add(viewTransactionButton);

                BlockTransaction blockTransaction = transactionEntry.getBlockTransaction();
                if(blockTransaction.getHeight() <= 0 && blockTransaction.getTransaction().isReplaceByFee() && transactionEntry.getWallet().allInputsFromWallet(blockTransaction.getHash())) {
                    Button increaseFeeButton = new Button("");
                    increaseFeeButton.setGraphic(getIncreaseFeeRBFGlyph());
                    increaseFeeButton.setOnAction(event -> {
                        increaseFee(transactionEntry);
                    });
                    actionBox.getChildren().add(increaseFeeButton);
                }

                if(blockTransaction.getHeight() <= 0 && containsWalletOutputs(transactionEntry)) {
                    Button cpfpButton = new Button("");
                    cpfpButton.setGraphic(getIncreaseFeeCPFPGlyph());
                    cpfpButton.setOnAction(event -> {
                        createCpfp(transactionEntry);
                    });
                    actionBox.getChildren().add(cpfpButton);
                }

                setGraphic(actionBox);
            } else if(entry instanceof NodeEntry) {
                NodeEntry nodeEntry = (NodeEntry)entry;
                Address address = nodeEntry.getAddress();
                setText(address.toString());
                setContextMenu(new AddressContextMenu(address, nodeEntry.getOutputDescriptor(), nodeEntry));
                Tooltip tooltip = new Tooltip();
                tooltip.setText(nodeEntry.getNode().toString());
                setTooltip(tooltip);
                getStyleClass().add("address-cell");

                HBox actionBox = new HBox();
                actionBox.getStyleClass().add("cell-actions");
                Button receiveButton = new Button("");
                receiveButton.setGraphic(getReceiveGlyph());
                receiveButton.setOnAction(event -> {
                    EventManager.get().post(new ReceiveActionEvent(nodeEntry));
                    Platform.runLater(() -> EventManager.get().post(new ReceiveToEvent(nodeEntry)));
                });
                actionBox.getChildren().add(receiveButton);

                if(canSignMessage(nodeEntry.getWallet())) {
                    Button signMessageButton = new Button("");
                    signMessageButton.setGraphic(getSignMessageGlyph());
                    signMessageButton.setOnAction(event -> {
                        MessageSignDialog messageSignDialog = new MessageSignDialog(nodeEntry.getWallet(), nodeEntry.getNode());
                        messageSignDialog.showAndWait();
                    });
                    actionBox.getChildren().add(signMessageButton);
                }

                setGraphic(actionBox);

                if(nodeEntry.getWallet().isWhirlpoolChildWallet()) {
                    setText(address.toString().substring(0, 20) + "...");
                    setContextMenu(null);
                    setGraphic(new HBox());
                }
            } else if(entry instanceof HashIndexEntry) {
                HashIndexEntry hashIndexEntry = (HashIndexEntry)entry;
                setText(hashIndexEntry.getDescription());
                setContextMenu(new HashIndexEntryContextMenu(getTreeTableView(), hashIndexEntry));
                Tooltip tooltip = new Tooltip();
                tooltip.setText(hashIndexEntry.getHashIndex().toString());
                setTooltip(tooltip);

                HBox actionBox = new HBox();
                actionBox.getStyleClass().add("cell-actions");
                Button viewTransactionButton = new Button("");
                viewTransactionButton.setGraphic(getViewTransactionGlyph());
                viewTransactionButton.setOnAction(event -> {
                    EventManager.get().post(new ViewTransactionEvent(this.getScene().getWindow(), hashIndexEntry.getBlockTransaction(), hashIndexEntry));
                });
                actionBox.getChildren().add(viewTransactionButton);

                if(hashIndexEntry.getType().equals(HashIndexEntry.Type.OUTPUT) && hashIndexEntry.isSpendable() && !hashIndexEntry.getHashIndex().isSpent()) {
                    Button spendUtxoButton = new Button("");
                    spendUtxoButton.setGraphic(getSendGlyph());
                    spendUtxoButton.setOnAction(event -> {
                        sendSelectedUtxos(getTreeTableView(), hashIndexEntry);
                    });
                    actionBox.getChildren().add(spendUtxoButton);
                }

                setGraphic(actionBox);
            }
        }
    }

    private static void increaseFee(TransactionEntry transactionEntry) {
        BlockTransaction blockTransaction = transactionEntry.getBlockTransaction();
        Map<BlockTransactionHashIndex, WalletNode> walletTxos = transactionEntry.getWallet().getWalletTxos();
        List<BlockTransactionHashIndex> utxos = transactionEntry.getChildren().stream()
                .filter(e -> e instanceof HashIndexEntry)
                .map(e -> (HashIndexEntry)e)
                .filter(e -> e.getType().equals(HashIndexEntry.Type.INPUT) && e.isSpendable())
                .map(e -> blockTransaction.getTransaction().getInputs().get((int)e.getHashIndex().getIndex()))
                .filter(TransactionInput::isReplaceByFeeEnabled)
                .map(txInput -> walletTxos.keySet().stream().filter(txo -> txo.getHash().equals(txInput.getOutpoint().getHash()) && txo.getIndex() == txInput.getOutpoint().getIndex()).findFirst().get())
                .collect(Collectors.toList());

        List<TransactionOutput> ourOutputs = transactionEntry.getChildren().stream()
                .filter(e -> e instanceof HashIndexEntry)
                .map(e -> (HashIndexEntry)e)
                .filter(e -> e.getType().equals(HashIndexEntry.Type.OUTPUT))
                .map(e -> e.getBlockTransaction().getTransaction().getOutputs().get((int)e.getHashIndex().getIndex()))
                .collect(Collectors.toList());

        List<TransactionOutput> consolidationOutputs = transactionEntry.getChildren().stream()
                .filter(e -> e instanceof HashIndexEntry)
                .map(e -> (HashIndexEntry)e)
                .filter(e -> e.getType().equals(HashIndexEntry.Type.OUTPUT) && e.getKeyPurpose() == KeyPurpose.RECEIVE)
                .map(e -> e.getBlockTransaction().getTransaction().getOutputs().get((int)e.getHashIndex().getIndex()))
                .collect(Collectors.toList());

        long changeTotal = ourOutputs.stream().mapToLong(TransactionOutput::getValue).sum();
        Transaction tx = blockTransaction.getTransaction();
        double vSize = tx.getVirtualSize();
        int inputSize = tx.getInputs().get(0).getLength() + (tx.getInputs().get(0).hasWitness() ? tx.getInputs().get(0).getWitness().getLength() / Transaction.WITNESS_SCALE_FACTOR : 0);
        List<BlockTransactionHashIndex> walletUtxos = new ArrayList<>(transactionEntry.getWallet().getWalletUtxos().keySet());
        Collections.shuffle(walletUtxos);
        while((double)changeTotal / vSize < getMaxFeeRate() && !walletUtxos.isEmpty()) {
            //If there is insufficent change output, include another random UTXO so the fee can be increased
            BlockTransactionHashIndex utxo = walletUtxos.remove(0);
            utxos.add(utxo);
            changeTotal += utxo.getValue();
            vSize += inputSize;
        }

        List<TransactionOutput> externalOutputs = new ArrayList<>(blockTransaction.getTransaction().getOutputs());
        externalOutputs.removeAll(ourOutputs);
        externalOutputs.addAll(consolidationOutputs);
        List<Payment> payments = externalOutputs.stream().map(txOutput -> {
            try {
                String label = transactionEntry.getLabel() == null ? "" : transactionEntry.getLabel();
                Matcher matcher = REPLACED_BY_FEE_SUFFIX.matcher(label);
                if(matcher.matches()) {
                    String base = matcher.group(1);
                    if(matcher.groupCount() > 2 && matcher.group(3) != null) {
                        int count = Integer.parseInt(matcher.group(3)) + 1;
                        label = base + "(Replaced By Fee #" + count + ")";
                    } else {
                        label = base + "(Replaced By Fee #2)";
                    }
                } else {
                    label += (label.isEmpty() ? "" : " ") + "(Replaced By Fee)";
                }

                return new Payment(txOutput.getScript().getToAddresses()[0], label, txOutput.getValue(), false);
            } catch(Exception e) {
                log.error("Error creating RBF payment", e);
                return null;
            }
        }).filter(Objects::nonNull).collect(Collectors.toList());

        if(payments.isEmpty()) {
            AppServices.showErrorDialog("Replace By Fee Error", "Error creating RBF transaction, check log for details");
            return;
        }

        EventManager.get().post(new SendActionEvent(transactionEntry.getWallet(), utxos));
        Platform.runLater(() -> EventManager.get().post(new SpendUtxoEvent(transactionEntry.getWallet(), utxos, payments, blockTransaction.getFee(), true)));
    }

    private static Double getMaxFeeRate() {
        if(AppServices.getTargetBlockFeeRates() == null || AppServices.getTargetBlockFeeRates().isEmpty()) {
            return 100.0;
        }

        return AppServices.getTargetBlockFeeRates().values().iterator().next();
    }

    private static void createCpfp(TransactionEntry transactionEntry) {
        BlockTransaction blockTransaction = transactionEntry.getBlockTransaction();
        List<BlockTransactionHashIndex> ourOutputs = transactionEntry.getChildren().stream()
                .filter(e -> e instanceof HashIndexEntry)
                .map(e -> (HashIndexEntry)e)
                .filter(e -> e.getType().equals(HashIndexEntry.Type.OUTPUT))
                .map(HashIndexEntry::getHashIndex)
                .collect(Collectors.toList());

        if(ourOutputs.isEmpty()) {
            throw new IllegalStateException("Cannot create CPFP without any wallet outputs to spend");
        }

        BlockTransactionHashIndex utxo = ourOutputs.get(0);

        WalletNode freshNode = transactionEntry.getWallet().getFreshNode(KeyPurpose.RECEIVE);
        String label = transactionEntry.getLabel() == null ? "" : transactionEntry.getLabel();
        label += (label.isEmpty() ? "" : " ") + "(CPFP)";
        Payment payment = new Payment(transactionEntry.getWallet().getAddress(freshNode), label, utxo.getValue(), true);

        EventManager.get().post(new SendActionEvent(transactionEntry.getWallet(), List.of(utxo)));
        Platform.runLater(() -> EventManager.get().post(new SpendUtxoEvent(transactionEntry.getWallet(), List.of(utxo), List.of(payment), blockTransaction.getFee(), false)));
    }

    private static boolean canSignMessage(Wallet wallet) {
        return wallet.getKeystores().size() == 1 && wallet.getScriptType() != ScriptType.P2TR &&
                (wallet.getKeystores().get(0).hasPrivateKey() || wallet.getKeystores().get(0).getSource() == KeystoreSource.HW_USB);
    }

    private static boolean containsWalletOutputs(TransactionEntry transactionEntry) {
        return transactionEntry.getChildren().stream()
                .filter(e -> e instanceof HashIndexEntry)
                .map(e -> (HashIndexEntry)e)
                .anyMatch(e -> e.getType().equals(HashIndexEntry.Type.OUTPUT));
    }

    private static void sendSelectedUtxos(TreeTableView<Entry> treeTableView, HashIndexEntry hashIndexEntry) {
        List<HashIndexEntry> utxoEntries = treeTableView.getSelectionModel().getSelectedCells().stream()
                .map(tp -> tp.getTreeItem().getValue())
                .filter(e -> e instanceof HashIndexEntry)
                .map(e -> (HashIndexEntry)e)
                .filter(e -> e.getType().equals(HashIndexEntry.Type.OUTPUT) && e.isSpendable())
                .collect(Collectors.toList());

        if(!utxoEntries.contains(hashIndexEntry)) {
            utxoEntries = List.of(hashIndexEntry);
        }

        final List<BlockTransactionHashIndex> spendingUtxos = utxoEntries.stream().map(HashIndexEntry::getHashIndex).collect(Collectors.toList());
        EventManager.get().post(new SendActionEvent(hashIndexEntry.getWallet(), spendingUtxos));
        Platform.runLater(() -> EventManager.get().post(new SpendUtxoEvent(hashIndexEntry.getWallet(), spendingUtxos)));
    }

    private static void freezeUtxo(TreeTableView<Entry> treeTableView, HashIndexEntry hashIndexEntry) {
        List<BlockTransactionHashIndex> utxos = treeTableView.getSelectionModel().getSelectedCells().stream()
                .map(tp -> tp.getTreeItem().getValue())
                .filter(e -> e instanceof HashIndexEntry && ((HashIndexEntry)e).getType().equals(HashIndexEntry.Type.OUTPUT))
                .map(e -> ((HashIndexEntry)e).getHashIndex())
                .filter(ref -> ref.getStatus() != Status.FROZEN)
                .collect(Collectors.toList());

        utxos.forEach(ref -> ref.setStatus(Status.FROZEN));
        EventManager.get().post(new WalletUtxoStatusChangedEvent(hashIndexEntry.getWallet(), utxos));
    }

    private static void unfreezeUtxo(TreeTableView<Entry> treeTableView, HashIndexEntry hashIndexEntry) {
        List<BlockTransactionHashIndex> utxos = treeTableView.getSelectionModel().getSelectedCells().stream()
                .map(tp -> tp.getTreeItem().getValue())
                .filter(e -> e instanceof HashIndexEntry && ((HashIndexEntry)e).getType().equals(HashIndexEntry.Type.OUTPUT))
                .map(e -> ((HashIndexEntry)e).getHashIndex())
                .filter(ref -> ref.getStatus() == Status.FROZEN)
                .collect(Collectors.toList());

        utxos.forEach(ref -> ref.setStatus(null));
        EventManager.get().post(new WalletUtxoStatusChangedEvent(hashIndexEntry.getWallet(), utxos));
    }

    private String getTooltip(TransactionEntry transactionEntry) {
        String tooltip = transactionEntry.getBlockTransaction().getHash().toString();
        if(transactionEntry.getBlockTransaction().getHeight() <= 0) {
            if(!AppServices.getMempoolHistogram().isEmpty()) {
                Set<MempoolRateSize> rateSizes = AppServices.getMempoolHistogram().get(AppServices.getMempoolHistogram().lastKey());
                double vSize = transactionEntry.getBlockTransaction().getTransaction().getVirtualSize();
                double feeRate = transactionEntry.getBlockTransaction().getFee() / vSize;
                long vSizefromTip = rateSizes.stream().filter(rateSize -> rateSize.getFee() > feeRate).mapToLong(MempoolRateSize::getVSize).sum();
                String amount = vSizefromTip + " vB";
                if(vSizefromTip > 1000 * 1000) {
                    amount = String.format("%.2f", (double)vSizefromTip / (1000 * 1000)) + " MvB";
                } else if(vSizefromTip > 1000) {
                    amount = String.format("%.2f", (double)vSizefromTip / 1000) + " kvB";
                }

                tooltip += "\nFee rate: " + String.format("%.2f", feeRate) + " sats/vB (" + amount + " from tip)";
            }

            tooltip += "\nRBF: " + (transactionEntry.getBlockTransaction().getTransaction().isReplaceByFee() ? "Enabled" : "Disabled");
        }

        return tooltip;
    }

    private static Glyph getViewTransactionGlyph() {
        Glyph searchGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.SEARCH);
        searchGlyph.setFontSize(12);
        return searchGlyph;
    }

    private static Glyph getIncreaseFeeRBFGlyph() {
        Glyph increaseFeeGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.HAND_HOLDING_MEDICAL);
        increaseFeeGlyph.setFontSize(12);
        return increaseFeeGlyph;
    }

    private static Glyph getIncreaseFeeCPFPGlyph() {
        Glyph cpfpGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.SIGN_OUT_ALT);
        cpfpGlyph.setFontSize(12);
        return cpfpGlyph;
    }

    private static Glyph getReceiveGlyph() {
        Glyph receiveGlyph = new Glyph("FontAwesome", FontAwesome.Glyph.ARROW_DOWN);
        receiveGlyph.setFontSize(12);
        return receiveGlyph;
    }

    private static Glyph getSignMessageGlyph() {
        Glyph signMessageGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.PEN_FANCY);
        signMessageGlyph.setFontSize(12);
        return signMessageGlyph;
    }

    private static Glyph getSendGlyph() {
        Glyph sendGlyph = new Glyph("FontAwesome", FontAwesome.Glyph.SEND);
        sendGlyph.setFontSize(12);
        return sendGlyph;
    }

    private static Glyph getCopyGlyph() {
        Glyph copyGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.COPY);
        copyGlyph.setFontSize(12);
        return copyGlyph;
    }

    private static Glyph getFreezeGlyph() {
        Glyph copyGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.SNOWFLAKE);
        copyGlyph.setFontSize(12);
        return copyGlyph;
    }

    private static Glyph getUnfreezeGlyph() {
        Glyph copyGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.SUN);
        copyGlyph.setFontSize(12);
        return copyGlyph;
    }

    private static class UnconfirmedTransactionContextMenu extends ContextMenu {
        public UnconfirmedTransactionContextMenu(TransactionEntry transactionEntry) {
            BlockTransaction blockTransaction = transactionEntry.getBlockTransaction();
            MenuItem viewTransaction = new MenuItem("View Transaction");
            viewTransaction.setGraphic(getViewTransactionGlyph());
            viewTransaction.setOnAction(AE -> {
                hide();
                EventManager.get().post(new ViewTransactionEvent(this.getOwnerWindow(), blockTransaction));
            });
            getItems().add(viewTransaction);

            if(blockTransaction.getTransaction().isReplaceByFee() && transactionEntry.getWallet().allInputsFromWallet(blockTransaction.getHash())) {
                MenuItem increaseFee = new MenuItem("Increase Fee (RBF)");
                increaseFee.setGraphic(getIncreaseFeeRBFGlyph());
                increaseFee.setOnAction(AE -> {
                    hide();
                    increaseFee(transactionEntry);
                });

                getItems().add(increaseFee);
            }

            if(containsWalletOutputs(transactionEntry)) {
                MenuItem createCpfp = new MenuItem("Increase Effective Fee (CPFP)");
                createCpfp.setGraphic(getIncreaseFeeCPFPGlyph());
                createCpfp.setOnAction(AE -> {
                    hide();
                    createCpfp(transactionEntry);
                });

                getItems().add(createCpfp);
            }

            MenuItem copyTxid = new MenuItem("Copy Transaction ID");
            copyTxid.setOnAction(AE -> {
                hide();
                ClipboardContent content = new ClipboardContent();
                content.putString(blockTransaction.getHashAsString());
                Clipboard.getSystemClipboard().setContent(content);
            });

            getItems().add(copyTxid);
        }
    }

    private static class TransactionContextMenu extends ContextMenu {
        public TransactionContextMenu(String date, BlockTransaction blockTransaction) {
            MenuItem viewTransaction = new MenuItem("View Transaction");
            viewTransaction.setGraphic(getViewTransactionGlyph());
            viewTransaction.setOnAction(AE -> {
                hide();
                EventManager.get().post(new ViewTransactionEvent(this.getOwnerWindow(), blockTransaction));
            });

            MenuItem copyDate = new MenuItem("Copy Date");
            copyDate.setOnAction(AE -> {
                hide();
                ClipboardContent content = new ClipboardContent();
                content.putString(date);
                Clipboard.getSystemClipboard().setContent(content);
            });

            MenuItem copyTxid = new MenuItem("Copy Transaction ID");
            copyTxid.setOnAction(AE -> {
                hide();
                ClipboardContent content = new ClipboardContent();
                content.putString(blockTransaction.getHashAsString());
                Clipboard.getSystemClipboard().setContent(content);
            });

            MenuItem copyHeight = new MenuItem("Copy Block Height");
            copyHeight.setOnAction(AE -> {
                hide();
                ClipboardContent content = new ClipboardContent();
                content.putString(blockTransaction.getHeight() > 0 ? Integer.toString(blockTransaction.getHeight()) : "Mempool");
                Clipboard.getSystemClipboard().setContent(content);
            });

            getItems().addAll(viewTransaction, copyDate, copyTxid, copyHeight);
        }
    }

    public static class AddressContextMenu extends ContextMenu {
        public AddressContextMenu(Address address, String outputDescriptor, NodeEntry nodeEntry) {
            MenuItem receiveToAddress = new MenuItem("Receive To");
            receiveToAddress.setGraphic(getReceiveGlyph());
            receiveToAddress.setOnAction(event -> {
                hide();
                EventManager.get().post(new ReceiveActionEvent(nodeEntry));
                Platform.runLater(() -> EventManager.get().post(new ReceiveToEvent(nodeEntry)));
            });
            getItems().add(receiveToAddress);

            if(nodeEntry != null && canSignMessage(nodeEntry.getWallet())) {
                MenuItem signVerifyMessage = new MenuItem("Sign/Verify Message");
                signVerifyMessage.setGraphic(getSignMessageGlyph());
                signVerifyMessage.setOnAction(AE -> {
                    hide();
                    MessageSignDialog messageSignDialog = new MessageSignDialog(nodeEntry.getWallet(), nodeEntry.getNode());
                    messageSignDialog.showAndWait();
                });
                getItems().add(signVerifyMessage);
            }

            MenuItem copyAddress = new MenuItem("Copy Address");
            copyAddress.setOnAction(AE -> {
                hide();
                ClipboardContent content = new ClipboardContent();
                content.putString(address.toString());
                Clipboard.getSystemClipboard().setContent(content);
            });

            MenuItem copyHex = new MenuItem("Copy Script Output Bytes");
            copyHex.setOnAction(AE -> {
                hide();
                ClipboardContent content = new ClipboardContent();
                content.putString(Utils.bytesToHex(address.getOutputScriptData()));
                Clipboard.getSystemClipboard().setContent(content);
            });

            MenuItem copyOutputDescriptor = new MenuItem("Copy Output Descriptor");
            copyOutputDescriptor.setOnAction(AE -> {
                hide();
                ClipboardContent content = new ClipboardContent();
                content.putString(outputDescriptor);
                Clipboard.getSystemClipboard().setContent(content);
            });

            getItems().addAll(copyAddress, copyHex, copyOutputDescriptor);
        }
    }

    private static class HashIndexEntryContextMenu extends ContextMenu {
        public HashIndexEntryContextMenu(TreeTableView<Entry> treeTableView, HashIndexEntry hashIndexEntry) {
            MenuItem viewTransaction = new MenuItem("View Transaction");
            viewTransaction.setGraphic(getViewTransactionGlyph());
            viewTransaction.setOnAction(AE -> {
                hide();
                EventManager.get().post(new ViewTransactionEvent(this.getOwnerWindow(), hashIndexEntry.getBlockTransaction()));
            });
            getItems().add(viewTransaction);

            if(hashIndexEntry.getType().equals(HashIndexEntry.Type.OUTPUT) && hashIndexEntry.isSpendable() && !hashIndexEntry.getHashIndex().isSpent()) {
                MenuItem sendSelected = new MenuItem("Send Selected");
                sendSelected.setGraphic(getSendGlyph());
                sendSelected.setOnAction(AE -> {
                    hide();
                    sendSelectedUtxos(treeTableView, hashIndexEntry);
                });
                getItems().add(sendSelected);
            }

            if(hashIndexEntry.getType().equals(HashIndexEntry.Type.OUTPUT) && !hashIndexEntry.getHashIndex().isSpent()) {
                if(hashIndexEntry.getHashIndex().getStatus() == null || hashIndexEntry.getHashIndex().getStatus() != Status.FROZEN) {
                    MenuItem freezeUtxo = new MenuItem("Freeze UTXO");
                    freezeUtxo.setGraphic(getFreezeGlyph());
                    freezeUtxo.setOnAction(AE -> {
                        hide();
                        freezeUtxo(treeTableView, hashIndexEntry);
                    });
                    getItems().add(freezeUtxo);
                } else {
                    MenuItem unfreezeUtxo = new MenuItem("Unfreeze UTXO");
                    unfreezeUtxo.setGraphic(getUnfreezeGlyph());
                    unfreezeUtxo.setOnAction(AE -> {
                        hide();
                        unfreezeUtxo(treeTableView, hashIndexEntry);
                    });
                    getItems().add(unfreezeUtxo);
                }
            }

            String label = "Copy " + (hashIndexEntry.getType().equals(HashIndexEntry.Type.OUTPUT) ? "Transaction Output" : "Transaction Input");
            MenuItem copyHashIndex = new MenuItem(label);
            copyHashIndex.setOnAction(AE -> {
                hide();
                ClipboardContent content = new ClipboardContent();
                content.putString(hashIndexEntry.getHashIndex().toString());
                Clipboard.getSystemClipboard().setContent(content);
            });
            getItems().add(copyHashIndex);
        }
    }

    public static void applyRowStyles(TreeTableCell<?, ?> cell, Entry entry) {
        cell.getStyleClass().remove("transaction-row");
        cell.getStyleClass().remove("node-row");
        cell.getStyleClass().remove("utxo-row");
        cell.getStyleClass().remove("address-cell");
        cell.getStyleClass().remove("hashindex-row");
        cell.getStyleClass().remove("confirming");
        cell.getStyleClass().remove("negative-amount");
        cell.getStyleClass().remove("spent");
        cell.getStyleClass().remove("unspendable");

        if(entry != null) {
            if(entry instanceof TransactionEntry) {
                cell.getStyleClass().add("transaction-row");
                TransactionEntry transactionEntry = (TransactionEntry)entry;
                if(transactionEntry.isConfirming()) {
                    cell.getStyleClass().add("confirming");
                    transactionEntry.confirmationsProperty().addListener((observable, oldValue, newValue) -> {
                        if(!transactionEntry.isConfirming()) {
                            cell.getStyleClass().remove("confirming");
                        }
                    });
                }
            } else if(entry instanceof NodeEntry) {
                cell.getStyleClass().add("node-row");
            } else if(entry instanceof UtxoEntry) {
                cell.getStyleClass().add("utxo-row");
                UtxoEntry utxoEntry = (UtxoEntry)entry;
                if(!utxoEntry.isSpendable()) {
                    cell.getStyleClass().add("unspendable");
                }
            } else if(entry instanceof HashIndexEntry) {
                cell.getStyleClass().add("hashindex-row");
                HashIndexEntry hashIndexEntry = (HashIndexEntry)entry;
                if(hashIndexEntry.isSpent()) {
                    cell.getStyleClass().add("spent");
                }
            }
        }
    }
}
