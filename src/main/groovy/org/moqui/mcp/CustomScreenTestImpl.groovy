/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.mcp

import org.moqui.impl.screen.ScreenTestImpl
import org.moqui.impl.context.ExecutionContextFactoryImpl

/**
 * Custom ScreenTest implementation for MCP access
 * This provides the necessary web context for screen rendering in MCP environment
 */
class CustomScreenTestImpl extends ScreenTestImpl {
    
    CustomScreenTestImpl(ExecutionContextFactoryImpl ecfi) {
        super(ecfi)
    }
    
    // Use the default makeWebFacade from ScreenTestImpl
    // It should work for basic screen rendering in MCP context
}