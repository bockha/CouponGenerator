package iuno.tdm.coupongenerator;

import org.bitcoinj.core.*;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.util.Arrays.asList;


/**
 * Copyright 2016 TRUMPF Werkzeugmaschinen GmbH + Co. KG
 * Created by Hans-Peter Bock on 07.12.2016.
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
public class CouponWallet {
    final NetworkParameters params = TestNet3Params.get();

    private Wallet feedWallet;
    private Wallet couponWallet;
    private BlockChain blockChain;
    private PeerGroup peerGroup;

    private static final String FEEDSUFFIX = ".feed";
    private static final String COUPONSUFFIX = ".coupon";
    private static final String WALLETSUFFIX = ".wallet";

    // todo enable class to create wallet systems from optional seed
    public CouponWallet(String couponName, boolean createWallets, boolean resetWallets) throws Exception {
        // construtcor
        String homeDir = System.getProperty("user.home");
        File chainFile = new File(homeDir, couponName + ".spvchain");
        File feedWalletFile = new File(homeDir, couponName + FEEDSUFFIX + WALLETSUFFIX);
        File couponWalletFile = new File(homeDir, couponName + COUPONSUFFIX + WALLETSUFFIX);

        // create new wallet system
        if (createWallets) {
            if (feedWalletFile.exists() || couponWalletFile.exists()) {
                System.out.println("!!! At least one wallet file already exists. No new wallet is created.");
                throw new IllegalStateException("At least one wallet file already exists. No new wallet is created.");
            }
            feedWallet = new Wallet(params);
            couponWallet = new Wallet(params);

        } else { // load existing wallet system
            feedWallet = Wallet.loadFromFile(feedWalletFile);
            couponWallet = Wallet.loadFromFile(couponWalletFile);
            if (resetWallets) {
                feedWallet.reset();;
                couponWallet.reset();
            }
        }

        // save wallet in case it had been just created or reset
        feedWallet.saveToFile(feedWalletFile);
        couponWallet.saveToFile(couponWalletFile);

        // auto save wallets at least every five seconds
        feedWallet.autosaveToFile(feedWalletFile, 5, TimeUnit.SECONDS, null);
        couponWallet.autosaveToFile(couponWalletFile, 5, TimeUnit.SECONDS, null);

        // eventually remove blockchainfile
        if ((createWallets || resetWallets) && chainFile.exists()) {
            chainFile.delete();
        }

        // initialize blockchain file
        List<Wallet> wallets = asList(feedWallet, couponWallet);
        blockChain = new BlockChain(params, wallets, new SPVBlockStore(params, chainFile));

        // initialize peer groupe
        peerGroup = new PeerGroup(params, blockChain);
        peerGroup.addWallet(feedWallet);
        peerGroup.addWallet(couponWallet);
    }

    public void startWalletSystem() {
        peerGroup.start();
        peerGroup.addPeerDiscovery(new DnsDiscovery(params));
    }

    public void downloadBlockChain() {
        peerGroup.downloadBlockChain();
    }

    public void stopWalletSystem() {
        peerGroup.stop();
    }

    public ArrayList<ECKey> generateCoupons(int number, Coin value) throws InsufficientMoneyException {
        SendRequest sr;

        Transaction tx = new Transaction(params);

        ArrayList<ECKey> coupons = new ArrayList<>();
        for (int i = 0; i < number; i++) {
            ECKey ek = couponWallet.freshReceiveKey();
            coupons.add(ek);
            tx.addOutput(value, ek.toAddress(params));
            System.out.printf("Address / Key: %s / %s\n", ek.toAddress(params), ek.getPrivateKeyAsWiF(params));
        }

        sr = SendRequest.forTx(tx);
        feedWallet.allowSpendingUnconfirmedTransactions();
        sr.feePerKb = Transaction.REFERENCE_DEFAULT_MIN_TX_FEE;
        feedWallet.completeTx(sr);
        try {
            feedWallet.commitTx(sr.tx);
            peerGroup.broadcastTransaction(sr.tx).broadcast().get();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return coupons;
    }

    public void sweepCoupons() throws InsufficientMoneyException {
        downloadBlockChain(); // essential here since coupons can be spent anytime
        SendRequest sr = SendRequest.emptyWallet(feedWallet.currentChangeAddress());
        sr.feePerKb = Transaction.REFERENCE_DEFAULT_MIN_TX_FEE;
        couponWallet.completeTx(sr);
        try {
            couponWallet.commitTx(sr.tx);
            peerGroup.broadcastTransaction(sr.tx).broadcast().get();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void showStatus() {
        downloadBlockChain();
        System.out.printf("Coupon wallet: %s (%s) %s\n",
                couponWallet.getBalance().toFriendlyString(),
                couponWallet.getBalance(Wallet.BalanceType.ESTIMATED).toFriendlyString(),
                couponWallet.getKeyChainSeed().getMnemonicCode());
        System.out.printf("Feed wallet: %s (%s) %s\n",
                feedWallet.getBalance().toFriendlyString(),
                feedWallet.getBalance(Wallet.BalanceType.ESTIMATED).toFriendlyString(),
                feedWallet.getKeyChainSeed().getMnemonicCode());
        System.out.printf("Feed wallet receive address: %s\n", feedWallet.currentReceiveAddress());
        System.out.flush();
    }

}
