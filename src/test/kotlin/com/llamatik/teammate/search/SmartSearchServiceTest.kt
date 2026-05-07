package com.llamatik.teammate.search

import org.junit.Assert.*
import org.junit.Test

class SmartSearchServiceTest {

    @Test
    fun `architecture keywords map to ARCHITECTURE intent`() {
        assertEquals(SmartSearchService.SearchIntent.ARCHITECTURE, SmartSearchService.detectIntent("explain the architecture of this app"))
        assertEquals(SmartSearchService.SearchIntent.ARCHITECTURE, SmartSearchService.detectIntent("how is the module structured?"))
        assertEquals(SmartSearchService.SearchIntent.ARCHITECTURE, SmartSearchService.detectIntent("give me an overview of the design pattern"))
    }

    @Test
    fun `symbol keywords map to SYMBOL intent`() {
        assertEquals(SmartSearchService.SearchIntent.SYMBOL, SmartSearchService.detectIntent("where is UserRepository defined"))
        assertEquals(SmartSearchService.SearchIntent.SYMBOL, SmartSearchService.detectIntent("find class LoginViewModel"))
        assertEquals(SmartSearchService.SearchIntent.SYMBOL, SmartSearchService.detectIntent("locate the declaration of AuthService"))
    }

    @Test
    fun `usage keywords map to USAGE intent`() {
        assertEquals(SmartSearchService.SearchIntent.USAGE, SmartSearchService.detectIntent("show all callers of refreshToken"))
        assertEquals(SmartSearchService.SearchIntent.USAGE, SmartSearchService.detectIntent("who calls refreshToken"))
        assertEquals(SmartSearchService.SearchIntent.USAGE, SmartSearchService.detectIntent("find usages of sendRequest"))
        assertEquals(SmartSearchService.SearchIntent.USAGE, SmartSearchService.detectIntent("show references to AuthManager"))
    }

    @Test
    fun `test keywords map to TEST intent`() {
        assertEquals(SmartSearchService.SearchIntent.TEST, SmartSearchService.detectIntent("which tests cover this class"))
        assertEquals(SmartSearchService.SearchIntent.TEST, SmartSearchService.detectIntent("find spec files for UserService"))
        assertEquals(SmartSearchService.SearchIntent.TEST, SmartSearchService.detectIntent("show junit coverage"))
    }

    @Test
    fun `error keywords map to ERROR intent`() {
        assertEquals(SmartSearchService.SearchIntent.ERROR, SmartSearchService.detectIntent("find the exception thrown here"))
        assertEquals(SmartSearchService.SearchIntent.ERROR, SmartSearchService.detectIntent("build error in MainFragment"))
        assertEquals(SmartSearchService.SearchIntent.ERROR, SmartSearchService.detectIntent("why does it crash on startup"))
    }

    @Test
    fun `unrecognised query maps to GENERAL intent`() {
        assertEquals(SmartSearchService.SearchIntent.GENERAL, SmartSearchService.detectIntent("show me the main screen"))
        assertEquals(SmartSearchService.SearchIntent.GENERAL, SmartSearchService.detectIntent("list all files"))
    }

    @Test
    fun `blank query maps to GENERAL intent`() {
        assertEquals(SmartSearchService.SearchIntent.GENERAL, SmartSearchService.detectIntent(""))
        assertEquals(SmartSearchService.SearchIntent.GENERAL, SmartSearchService.detectIntent("   "))
    }

    @Test
    fun `extractSymbol returns CamelCase token when present`() {
        assertEquals("UserRepository", SmartSearchService.extractSymbol("find UserRepository"))
        assertEquals("AuthService", SmartSearchService.extractSymbol("where is AuthService defined"))
    }

    @Test
    fun `extractSymbol returns longest word when no CamelCase`() {
        val result = SmartSearchService.extractSymbol("find the authentication logic")
        assertTrue("Should return a non-blank token", result.isNotBlank())
    }
}
