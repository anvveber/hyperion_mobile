package io.github.anvveber.hyperion

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat

/** First-launch screen (and "Change server"): asks for the server address and checks it. */
class SetupActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var connect: Button
    private lateinit var continueAnyway: Button

    /** The address that failed or could not be confirmed, kept for "Continue anyway". */
    private var pending: Uri? = null
    private var checking: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_setup)

        val root = findViewById<View>(R.id.root)
        root.padForSystemBars()
        val density = resources.displayMetrics.density
        root.background = GridDrawable(
            getColor(R.color.background),
            getColor(R.color.grid_line),
            step = 32 * density,
            lineWidth = density,
        )

        input = findViewById(R.id.server_input)
        status = findViewById(R.id.status)
        connect = findViewById(R.id.connect)
        continueAnyway = findViewById(R.id.continue_anyway)

        val current = ServerConfig.get(this)
        if (current != null) {
            if (savedInstanceState == null) input.setText(current.toString())
            findViewById<Button>(R.id.cancel).apply {
                visibility = View.VISIBLE
                setOnClickListener { finish() }
            }
        }

        connect.setOnClickListener { submit() }
        continueAnyway.setOnClickListener { pending?.let { finishWith(it) } }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submit()
                true
            } else {
                false
            }
        }
    }

    override fun onDestroy() {
        checking?.interrupt()
        checking = null
        super.onDestroy()
    }

    private fun submit() {
        pending = null
        continueAnyway.visibility = View.GONE

        val text = input.text.toString()
        if (text.isBlank()) {
            showStatus(getString(R.string.setup_error_empty), warning = true)
            return
        }
        val server = ServerConfig.normalize(text)
        if (server == null) {
            showStatus(getString(R.string.setup_error_invalid), warning = true)
            return
        }
        input.setText(server.toString())

        val prefix = if (server.scheme == "http") getString(R.string.setup_warning_http) + "\n\n" else ""
        showStatus(prefix + getString(R.string.setup_checking), warning = false)
        setBusy(true)

        checking = Thread {
            val result = ServerCheck.run(server)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                when (result) {
                    ServerCheck.Result.Ok -> finishWith(server)
                    ServerCheck.Result.LoginPage -> {
                        Toast.makeText(this, R.string.setup_login_page, Toast.LENGTH_LONG).show()
                        finishWith(server)
                    }
                    ServerCheck.Result.Unconfirmed -> offerContinue(server, prefix + getString(R.string.setup_error_unconfirmed))
                    is ServerCheck.Result.Unreachable -> offerContinue(
                        server,
                        prefix + getString(R.string.setup_error_unreachable, result.reason),
                    )
                }
            }
        }.also { it.start() }
    }

    private fun offerContinue(server: Uri, message: String) {
        pending = server
        showStatus(message, warning = true)
        continueAnyway.visibility = View.VISIBLE
    }

    private fun finishWith(server: Uri) {
        ServerConfig.set(this, server)
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    private fun setBusy(busy: Boolean) {
        connect.isEnabled = !busy
        input.isEnabled = !busy
    }

    private fun showStatus(message: String, warning: Boolean) {
        status.visibility = View.VISIBLE
        status.text = message
        status.setTextColor(getColor(if (warning) R.color.warning else R.color.secondary))
    }
}
