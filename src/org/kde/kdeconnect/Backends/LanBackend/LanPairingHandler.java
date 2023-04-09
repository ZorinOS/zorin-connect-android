/*
 * SPDX-FileCopyrightText: 2015 Vineet Garg <grg.vineet@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect.Backends.LanBackend;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.kde.kdeconnect.Backends.BasePairingHandler;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.NetworkPacket;
import com.zorinos.zorin_connect.R;

import java.security.cert.CertificateEncodingException;
import java.util.Timer;
import java.util.TimerTask;

public class LanPairingHandler extends BasePairingHandler {

    private Timer mPairingTimer;

    public LanPairingHandler(Device device, final PairingHandlerCallback callback) {
        super(device, callback);

        if (device.isPaired()) {
            mPairStatus = PairStatus.Paired;
        } else {
            mPairStatus = PairStatus.NotPaired;
        }
    }

    private NetworkPacket createPairPacket() {
        NetworkPacket np = new NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR);
        np.set("pair", true);
        return np;
    }

    @Override
    public void packetReceived(NetworkPacket np) {

        boolean wantsPair = np.getBoolean("pair");

        if (wantsPair == isPaired()) {
            if (mPairStatus == PairStatus.Requested) {
                //Log.e("Device","Unpairing (pair rejected)");
                mPairStatus = PairStatus.NotPaired;
                hidePairingNotification();
                mCallback.pairingFailed(mDevice.getContext().getString(R.string.error_canceled_by_other_peer));
            }
            return;
        }

        if (wantsPair) {

            if (mPairStatus == PairStatus.Requested)  { //We started pairing

                hidePairingNotification();

                pairingDone();

            } else {

                // If device is already paired, accept pairing silently
                if (mDevice.isPaired()) {
                    acceptPairing();
                    return;
                }

                // Pairing notifications are still managed by device as there is no other way to
                // know about notificationId to cancel notification when PairActivity is started
                // Even putting notificationId in intent does not work because PairActivity can be
                // started from MainActivity too, so then notificationId cannot be set
                hidePairingNotification();
                mDevice.displayPairingNotification();

                mPairingTimer = new Timer();

                mPairingTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        Log.w("KDE/Device","Unpairing (timeout B)");
                        mPairStatus = PairStatus.NotPaired;
                        hidePairingNotification();
                    }
                }, 25*1000); //Time to show notification, waiting for user to accept (peer will timeout in 30 seconds)
                mPairStatus = PairStatus.RequestedByPeer;
                mCallback.incomingRequest();

            }
        } else {
            Log.i("KDE/Pairing", "Unpair request");

            if (mPairStatus == PairStatus.Requested) {
                hidePairingNotification();
                mCallback.pairingFailed(mDevice.getContext().getString(R.string.error_canceled_by_other_peer));
            } else if (mPairStatus == PairStatus.Paired) {
                mCallback.unpaired();
            }

            mPairStatus = PairStatus.NotPaired;

        }

    }

    @Override
    public void requestPairing() {

        Device.SendPacketStatusCallback statusCallback = new Device.SendPacketStatusCallback() {
            @Override
            public void onSuccess() {
                hidePairingNotification(); //Will stop the pairingTimer if it was running
                mPairingTimer = new Timer();
                mPairingTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        mCallback.pairingFailed(mDevice.getContext().getString(R.string.error_timed_out));
                        Log.w("KDE/Device","Unpairing (timeout A)");
                        mPairStatus = PairStatus.NotPaired;
                    }
                }, 30*1000); //Time to wait for the other to accept
                mPairStatus = PairStatus.Requested;
            }

            @Override
            public void onFailure(Throwable e) {
                Log.e("LanPairing/onFailure", "Exception", e);
                mCallback.pairingFailed(mDevice.getContext().getString(R.string.runcommand_notreachable));
            }
        };
        mDevice.sendPacket(createPairPacket(), statusCallback);
    }

    private void hidePairingNotification() {
        mDevice.hidePairingNotification();
        if (mPairingTimer != null) {
            mPairingTimer .cancel();
        }
    }

    @Override
    public void acceptPairing() {
        hidePairingNotification();
        Device.SendPacketStatusCallback statusCallback = new Device.SendPacketStatusCallback() {
            @Override
            public void onSuccess() {
                pairingDone();
            }

            @Override
            public void onFailure(Throwable e) {
                Log.e("LanPairing/onFailure", "Exception", e);
                mCallback.pairingFailed(mDevice.getContext().getString(R.string.error_not_reachable));
            }
        };
        mDevice.sendPacket(createPairPacket(), statusCallback);
    }

    @Override
    public void rejectPairing() {
        hidePairingNotification();
        mPairStatus = PairStatus.NotPaired;
        NetworkPacket np = new NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR);
        np.set("pair", false);
        mDevice.sendPacket(np);
    }

    private void pairingDone() {
        // Store device information needed to create a Device object in a future
        //Log.e("KDE/PairingDone", "Pairing Done");
        SharedPreferences.Editor editor = mDevice.getContext().getSharedPreferences(mDevice.getDeviceId(), Context.MODE_PRIVATE).edit();

        try {
            String encodedCertificate = Base64.encodeToString(mDevice.certificate.getEncoded(), 0);
            editor.putString("certificate", encodedCertificate);
        } catch (NullPointerException n) {
            Log.w("KDE/PairingDone", "Certificate is null, remote device does not support ssl", n);
        } catch (CertificateEncodingException c) {
            Log.e("KDE/PairingDOne", "Error encoding certificate", c);
        } catch (Exception e) {
            Log.e("KDE/Pairng", "Exception", e);
        }
        editor.apply();

        mPairStatus = PairStatus.Paired;
        mCallback.pairingDone();

    }

    @Override
    public void unpair() {
        mPairStatus = PairStatus.NotPaired;
        NetworkPacket np = new NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR);
        np.set("pair", false);
        mDevice.sendPacket(np);
    }
}
