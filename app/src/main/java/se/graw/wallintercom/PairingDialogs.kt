package se.graw.wallintercom

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast

/** The pairing dialogs, on both tablets. */
object PairingDialogs {
    private val main = Handler(Looper.getMainLooper())

    /**
     * This tablet asks the one at [ip] to pair: "Pair with Kitchen?" → waiting with the code →
     * done. [onPaired] runs after a successful pairing, [onFinished] always at the end.
     */
    fun pair(
        act: Activity, ip: String, port: Int, knownName: String?,
        onPaired: (Peer) -> Unit, onFinished: () -> Unit = {},
    ) {
        val label = knownName ?: act.getString(R.string.tablet_at, ip)
        AlertDialog.Builder(act)
            .setTitle(act.getString(R.string.pair_title, label))
            .setMessage(act.getString(R.string.pair_explain, label))
            .setPositiveButton(R.string.pair) { _, _ -> start(act, ip, port, label, onPaired, onFinished) }
            .setNegativeButton(R.string.cancel) { _, _ -> onFinished() }
            .setOnCancelListener { onFinished() }
            .show()
            .bigText()
    }

    private fun start(act: Activity, ip: String, port: Int, label: String, onPaired: (Peer) -> Unit, onFinished: () -> Unit) {
        var pairing: Link.Pairing? = null
        val waiting = AlertDialog.Builder(act)
            .setTitle(act.getString(R.string.pair_title, label))
            .setMessage(act.getString(R.string.pair_contacting, label))
            .setNegativeButton(R.string.cancel) { _, _ ->
                pairing?.cancel()
                onFinished()
            }
            .setCancelable(false)
            .show()
            .bigText()
        pairing = Link.pair(Prefs(act), ip, port, object : Link.PairListener {
            override fun onCode(name: String, code: String) = main.post {
                waiting.setTitle(act.getString(R.string.pair_title, name))
                waiting.setMessage(act.getString(R.string.pair_waiting, code, name))
            }.let {}

            override fun onPaired(peer: Peer) = main.post {
                waiting.dismissSafely()
                if (!act.isFinishing) Toast.makeText(act, act.getString(R.string.pair_ok, peer.name), Toast.LENGTH_LONG).show()
                onPaired(peer)
                onFinished()
            }.let {}

            override fun onFailed(why: Link.PairFailure, name: String?) = main.post {
                waiting.dismissSafely()
                val n = name ?: label
                val text = when (why) {
                    Link.PairFailure.UNREACHABLE -> act.getString(R.string.err_unreachable, n)
                    Link.PairFailure.DECLINED -> act.getString(R.string.pair_declined, n)
                    Link.PairFailure.BUSY -> act.getString(R.string.pair_busy, n)
                    Link.PairFailure.ALREADY_PAIRING -> act.getString(R.string.pair_already, n)
                    Link.PairFailure.TIMEOUT -> act.getString(R.string.pair_timeout, n)
                    Link.PairFailure.VERSION -> act.getString(R.string.pair_version, n)
                }
                if (!act.isFinishing) {
                    AlertDialog.Builder(act).setMessage(text).setPositiveButton(R.string.ok, null).show().bigText()
                }
                onFinished()
            }.let {}
        })
    }

    /** Another tablet asks to pair with this one. */
    fun request(act: Activity, name: String, code: String, onAnswer: (Boolean) -> Unit): AlertDialog =
        AlertDialog.Builder(act)
            .setTitle(act.getString(R.string.pair_request_title, name))
            .setMessage(act.getString(R.string.pair_request_msg, code, name))
            .setPositiveButton(R.string.accept) { _, _ -> onAnswer(true) }
            .setNegativeButton(R.string.decline) { _, _ -> onAnswer(false) }
            .setCancelable(false)
            .show()
            .bigText()

    /** Big enough to read the code from across the room. */
    private fun AlertDialog.bigText(): AlertDialog {
        findViewById<TextView>(android.R.id.message)?.textSize = 20f
        return this
    }

    private fun AlertDialog.dismissSafely() = try {
        dismiss()
    } catch (_: Exception) {
    }
}
