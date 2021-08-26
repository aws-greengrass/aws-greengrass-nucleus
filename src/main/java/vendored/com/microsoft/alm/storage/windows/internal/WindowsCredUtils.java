// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.
// Adapted from CredManagerBackedSecureStore

package vendored.com.microsoft.alm.storage.windows.internal;

import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.util.Arrays;

public class WindowsCredUtils {

    private WindowsCredUtils() {
    }

    /**
     * Retrieves a credential entry.
     *
     */
    public static byte[] read(String key) throws IOException {
        final CredAdvapi32.PCREDENTIAL pCredential = new CredAdvapi32.PCREDENTIAL();
        try {
            // MSDN doc doesn't mention threading safety, so let's just be careful and synchronize the access
            synchronized (CredAdvapi32.INSTANCE) {
                CredAdvapi32.INSTANCE.CredRead(key, CredAdvapi32.CRED_TYPE_GENERIC, 0, pCredential);
            }
            final CredAdvapi32.CREDENTIAL credential = new CredAdvapi32.CREDENTIAL(pCredential.credential);
            return credential.CredentialBlob.getByteArray(0, credential.CredentialBlobSize);
        } catch (LastErrorException e) {
            throw new IOException("Failed to read credential", e);
        } finally {
            if (pCredential.credential != null) {
                synchronized (CredAdvapi32.INSTANCE) {
                    CredAdvapi32.INSTANCE.CredFree(pCredential.credential);
                }
            }
        }
    }

    /**
     * Deletes a credential entry.
     */
    public static boolean delete(String key) throws IOException {
        try {
            synchronized (CredAdvapi32.INSTANCE) {
                return CredAdvapi32.INSTANCE.CredDelete(key, CredAdvapi32.CRED_TYPE_GENERIC, 0);
            }
        } catch (LastErrorException e) {
            throw new IOException("Failed to delete credential", e);
        }
    }

    /**
     * Add credential entry. Will overwrite if the entry key already exists.
     * Caveat: the given blob array will be zeroed-out for security.
     */
    public static void add(String key, byte[] blob) throws IOException {
        CredAdvapi32.CREDENTIAL cred = buildCred(key, blob);
        try {
            synchronized (CredAdvapi32.INSTANCE) {
                CredAdvapi32.INSTANCE.CredWrite(cred, 0);
            }
        } catch (LastErrorException e) {
            throw new IOException("Failed to add credential", e);
        } finally {
            cred.CredentialBlob.clear(blob.length);
            Arrays.fill(blob, (byte) 0);
        }
    }

    private static CredAdvapi32.CREDENTIAL buildCred(String key, byte[] credentialBlob) {
        final CredAdvapi32.CREDENTIAL credential = new CredAdvapi32.CREDENTIAL();

        credential.Flags = 0;
        credential.Type = CredAdvapi32.CRED_TYPE_GENERIC;
        credential.TargetName = key;

        credential.CredentialBlobSize = credentialBlob.length;
        credential.CredentialBlob = getPointer(credentialBlob);

        credential.Persist = CredAdvapi32.CRED_PERSIST_LOCAL_MACHINE;
        credential.UserName = key;

        return credential;
    }

    private static Pointer getPointer(byte[] array) {
        Pointer p = new Memory(array.length);
        p.write(0, array, 0, array.length);
        return p;
    }
}
