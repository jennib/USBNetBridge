package com.jenniferbeidas.usbnetserver

import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver

abstract class RtpAdapter : PeerConnection.Observer {
    abstract fun requestKeyFrame()

    override fun onTrack(transceiver: RtpTransceiver) {
        // The call to transceiver.receiver.setObserver() was causing a build error.
        // This method does not seem to exist in the WebRTC library version used in this project.
        // I'll need to find an alternative way to listen for RTCP packets.
    }

    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}

    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}

    override fun onIceConnectionReceivingChange(p0: Boolean) {}

    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}

    override fun onIceCandidate(p0: IceCandidate?) {}

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}

    override fun onAddStream(p0: MediaStream?) {}

    override fun onRemoveStream(p0: MediaStream?) {}

    override fun onDataChannel(p0: DataChannel?) {}

    override fun onRenegotiationNeeded() {}
}
