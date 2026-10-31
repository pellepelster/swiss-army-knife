package io.pelle.sak.grocery

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.pelle.sak.grocery.ui.GroceryApp
import io.pelle.sak.grocery.ui.GroceryTheme

// the one activity; it takes the login links the signin page of the web app hands on
// (io.pelle.sak.grocery://signin?token=…, FDR-005)
class MainActivity : ComponentActivity() {
    private val viewModel: GroceryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // not again after a rotation, the link was used
        if (savedInstanceState == null) signIn(intent)
        setContent {
            GroceryTheme {
                GroceryApp(viewModel.model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        signIn(intent)
    }

    private fun signIn(intent: Intent?) {
        val link = intent?.data ?: return
        if (link.scheme != packageName || link.host != "signin") return
        link.getQueryParameter("token")?.takeIf { it.isNotEmpty() }?.let { viewModel.model.signInWithLink(it) }
        // the token is used once, a recreated activity must not find it again
        setIntent(Intent(intent).setData(null))
    }
}
