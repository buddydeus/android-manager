package com.buddydeus.androidmanager

import android.database.MatrixCursor
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buddydeus.androidmanager.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentQueryTest {
    private class Meta:AuthMetadataStore {
        override fun read()=AuthMetadata(directory="content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        override fun write(value:AuthMetadata)=Unit
    }
    @Test fun providerErrorIsNeverClassifiedAsDeletedPassword() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=SafPasswordRepository(context,Meta()) { _,columns -> MatrixCursor(columns).apply { extras=Bundle().apply { putString(DocumentsContract.EXTRA_ERROR,"provider unavailable") } } }
        assertTrue(repo.inspect() is PasswordFileState.Broken)
    }
    @Test fun completedEmptyDirectoryIsClassifiedAsMissing() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=SafPasswordRepository(context,Meta()) { _,columns -> MatrixCursor(columns) }
        assertEquals(PasswordFileState.Missing,repo.inspect())
    }
}
