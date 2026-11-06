package com.buddydeus.androidmanager.application
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UpgradeRequestsTest {
    @Test fun notificationDuringAnEmptyResponseIsConsumedAfterThatRunReturns()=runBlocking {
        val requests=UpgradeRequests();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val second=CompletableDeferred<String>()
        var calls=0
        val job=launch {
            requests.drain { token -> if(++calls==1) { entered.complete(Unit);release.await() } else second.complete(token) }
        }
        try {
            requests.request("first");withTimeout(1000) { entered.await() }
            requests.request("intermediate");requests.request("latest")
            release.complete(Unit)
            assertEquals("latest",withTimeout(1000) { second.await() })
            requests.close();job.join();assertEquals(2,calls)
        } finally { requests.close();job.cancelAndJoin() }
    }
}
