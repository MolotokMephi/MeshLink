package team.hex.meshlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import team.hex.meshlink.crypto.Crypto
import team.hex.meshlink.crypto.MnemonicBackup

class MnemonicBackupTest {

    @Test fun `phrase round trips identity`() {
        val id = Crypto.generateIdentity()
        val phrase = MnemonicBackup.exportPhrase(id)
        val recovered = MnemonicBackup.importPhrase(phrase)
        assertNotNull(recovered)
        assertArrayEquals(id.edPriv, recovered!!.edPriv)
        assertArrayEquals(id.edPub, recovered.edPub)
        assertArrayEquals(id.xPriv, recovered.xPriv)
        assertArrayEquals(id.xPub, recovered.xPub)
    }

    @Test fun `bad checksum is rejected`() {
        val id = Crypto.generateIdentity()
        val phrase = MnemonicBackup.exportPhrase(id)
        // Flip a character in the middle so the checksum mismatches.
        val cleaned = phrase.replace("-", "")
        val flipped = cleaned.substring(0, 30) + (if (cleaned[30] == '0') '1' else '0') + cleaned.substring(31)
        val recovered = MnemonicBackup.importPhrase("meshlink-recovery:1:$flipped")
        assertNull(recovered)
    }
}
