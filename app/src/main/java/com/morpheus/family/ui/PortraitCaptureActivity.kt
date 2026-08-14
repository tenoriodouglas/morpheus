package com.morpheus.family.ui

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * QR scanner locked to portrait.
 *
 * The library's default capture activity follows the orientation sensor, so the
 * preview flips to landscape while the user is holding the phone upright to scan
 * the child's code. Orientation is pinned in the manifest entry for this class.
 */
class PortraitCaptureActivity : CaptureActivity()
