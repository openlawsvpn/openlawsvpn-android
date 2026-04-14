// Copyright (C) 2026 openlawsvpn contributors. All rights reserved.
package com.openlawsvpn.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.openlawsvpn.android.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: content draws behind status bar and navigation bar.
        // Enforced by default on Android 15+; call explicitly so we can handle insets.
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply system bar insets: top padding pushes content below the status bar,
        // bottom padding on the BottomNavigationView clears the gesture/nav bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.navHostFragment.setPadding(bars.left, bars.top, bars.right, 0)
            binding.bottomNav.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        binding.bottomNav.setupWithNavController(navHost.navController)
    }

    /**
     * Handles the `openlawsvpn://saml-callback` deep-link that Chrome fires after the user
     * completes SAML login. The SAMLResponse was already captured by SamlCallbackServer before
     * the redirect — this intent just brings the activity back to the foreground.
     *
     * SECURITY: Validate scheme + host before acting. Any installed app can fire an intent
     * to an exported Activity; rejecting unknown schemes prevents unintended side-effects.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data?.scheme == "openlawsvpn" && intent.data?.host == "saml-callback") {
            setIntent(intent)
        }
    }
}
