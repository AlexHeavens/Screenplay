/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/**
 * Library.pending lives in memory and outlives a test, so a failed import row left by one test hides the empty shelf
 * from the next one. Clearing it before each test keeps every test independent of run order.
 */
class PendingImportCleaner : RunListener() {
    override fun testStarted(description: Description) {
        Library.pending.value.forEach { Library.finish(it.key) }
    }
}
