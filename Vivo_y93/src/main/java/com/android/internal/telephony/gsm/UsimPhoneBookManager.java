package com.android.internal.telephony.gsm;

import android.annotation.VivoHook;
import android.annotation.VivoHook.VivoHookType;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.telephony.Rlog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import com.android.internal.telephony.uicc.AdnRecord;
import com.android.internal.telephony.uicc.AdnRecordCache;
import com.android.internal.telephony.uicc.IccConstants;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.IccUtils;
import java.util.ArrayList;

public class UsimPhoneBookManager extends Handler implements IccConstants {
    private static final boolean DBG = true;
    private static final int EVENT_EMAIL_LOAD_DONE = 4;
    private static final int EVENT_IAP_LOAD_DONE = 3;
    private static final int EVENT_PBR_LOAD_DONE = 1;
    private static final int EVENT_USIM_ADN_LOAD_DONE = 2;
    private static final byte INVALID_BYTE = (byte) -1;
    private static final int INVALID_SFI = -1;
    private static final String LOG_TAG = "UsimPhoneBookManager";
    private static final int USIM_EFAAS_TAG = 199;
    private static final int USIM_EFADN_TAG = 192;
    private static final int USIM_EFANR_TAG = 196;
    private static final int USIM_EFCCP1_TAG = 203;
    private static final int USIM_EFEMAIL_TAG = 202;
    private static final int USIM_EFEXT1_TAG = 194;
    private static final int USIM_EFGRP_TAG = 198;
    private static final int USIM_EFGSD_TAG = 200;
    private static final int USIM_EFIAP_TAG = 193;
    private static final int USIM_EFPBC_TAG = 197;
    private static final int USIM_EFSNE_TAG = 195;
    private static final int USIM_EFUID_TAG = 201;
    private static final int USIM_TYPE1_TAG = 168;
    private static final int USIM_TYPE2_TAG = 169;
    private static final int USIM_TYPE3_TAG = 170;
    private AdnRecordCache mAdnCache;
    private ArrayList<byte[]> mEmailFileRecord;
    private SparseArray<ArrayList<String>> mEmailsForAdnRec;
    private IccFileHandler mFh;
    private ArrayList<byte[]> mIapFileRecord;
    private Boolean mIsPbrPresent;
    private Object mLock = new Object();
    private ArrayList<PbrRecord> mPbrRecords;
    private ArrayList<AdnRecord> mPhoneBookRecords;
    private boolean mRefreshCache = false;
    private SparseIntArray mSfiEfidTable;

    private class File {
        private final int mEfid;
        private final int mIndex;
        private final int mParentTag;
        private final int mSfi;

        File(int parentTag, int efid, int sfi, int index) {
            this.mParentTag = parentTag;
            this.mEfid = efid;
            this.mSfi = sfi;
            this.mIndex = index;
        }

        public int getParentTag() {
            return this.mParentTag;
        }

        public int getEfid() {
            return this.mEfid;
        }

        public int getSfi() {
            return this.mSfi;
        }

        public int getIndex() {
            return this.mIndex;
        }
    }

    private class PbrRecord {
        private SparseArray<File> mFileIds = new SparseArray();
        private int mMasterFileRecordNum;

        PbrRecord(byte[] record) {
            UsimPhoneBookManager.this.log("PBR rec: " + IccUtils.bytesToHexString(record));
            parseTag(new SimTlv(record, 0, record.length));
        }

        void parseTag(SimTlv tlv) {
            do {
                int tag = tlv.getTag();
                switch (tag) {
                    case 168:
                    case 169:
                    case 170:
                        byte[] data = tlv.getData();
                        parseEfAndSFI(new SimTlv(data, 0, data.length), tag);
                        break;
                }
            } while (tlv.nextObject());
        }

        @VivoHook(hookType = VivoHookType.CHANGE_CODE)
        void parseEfAndSFI(SimTlv tlv, int parentTag) {
            int tagNumberWithinParentTag = 0;
            do {
                int tag = tlv.getTag();
                UsimPhoneBookManager.this.log("UsimPhoneBookManager parseEfAndSFI tag is " + tag);
                switch (tag) {
                    case 192:
                    case 193:
                    case 194:
                    case 195:
                    case 196:
                    case 197:
                    case UsimPhoneBookManager.USIM_EFGRP_TAG /*198*/:
                    case UsimPhoneBookManager.USIM_EFAAS_TAG /*199*/:
                    case 200:
                    case UsimPhoneBookManager.USIM_EFUID_TAG /*201*/:
                    case UsimPhoneBookManager.USIM_EFEMAIL_TAG /*202*/:
                    case UsimPhoneBookManager.USIM_EFCCP1_TAG /*203*/:
                        int sfi = -1;
                        byte[] data = tlv.getData();
                        if (data.length >= 2 && data.length <= 3) {
                            if (data.length == 3) {
                                sfi = data[2] & 255;
                            }
                            this.mFileIds.put(tag, new File(parentTag, ((data[0] & 255) << 8) | (data[1] & 255), sfi, tagNumberWithinParentTag));
                            break;
                        }
                        UsimPhoneBookManager.this.log("Invalid TLV length: " + data.length);
                        break;
                        break;
                }
                tagNumberWithinParentTag++;
            } while (tlv.nextObject());
        }
    }

    public UsimPhoneBookManager(IccFileHandler fh, AdnRecordCache cache) {
        this.mFh = fh;
        this.mPhoneBookRecords = new ArrayList();
        this.mPbrRecords = null;
        this.mIsPbrPresent = Boolean.valueOf(true);
        this.mAdnCache = cache;
        this.mEmailsForAdnRec = new SparseArray();
        this.mSfiEfidTable = new SparseIntArray();
    }

    @VivoHook(hookType = VivoHookType.CHANGE_CODE)
    public void reset() {
        this.mPhoneBookRecords.clear();
        this.mIapFileRecord = null;
        this.mEmailFileRecord = null;
        this.mPbrRecords = null;
        this.mIsPbrPresent = Boolean.valueOf(true);
        this.mRefreshCache = false;
        this.mEmailsForAdnRec.clear();
        this.mSfiEfidTable.clear();
        log("UsimPhoneBookManager reset over!");
    }

    @VivoHook(hookType = VivoHookType.CHANGE_CODE)
    public ArrayList<AdnRecord> loadEfFilesFromUsim() {
        synchronized (this.mLock) {
            if (!this.mPhoneBookRecords.isEmpty()) {
                if (this.mRefreshCache) {
                    this.mRefreshCache = false;
                    refreshCache();
                }
                ArrayList<AdnRecord> arrayList = this.mPhoneBookRecords;
                return arrayList;
            } else if (this.mIsPbrPresent.booleanValue()) {
                if (this.mPbrRecords == null) {
                    readPbrFileAndWait();
                }
                if (this.mPbrRecords == null) {
                    return null;
                }
                int numRecs = this.mPbrRecords.size();
                log("loadEfFilesFromUsim: Loading adn and emails");
                for (int i = 0; i < numRecs; i++) {
                    readAdnFileAndWait(i);
                }
                updatePhoneAdnRecord();
                return this.mPhoneBookRecords;
            } else {
                return null;
            }
        }
    }

    private void refreshCache() {
        if (this.mPbrRecords != null) {
            this.mPhoneBookRecords.clear();
            int numRecs = this.mPbrRecords.size();
            for (int i = 0; i < numRecs; i++) {
                readAdnFileAndWait(i);
            }
        }
    }

    public void invalidateCache() {
        this.mRefreshCache = true;
    }

    private void readPbrFileAndWait() {
        this.mFh.loadEFLinearFixedAll(IccConstants.EF_PBR, obtainMessage(1));
        try {
            this.mLock.wait();
        } catch (InterruptedException e) {
            Rlog.e(LOG_TAG, "Interrupted Exception in readAdnFileAndWait");
        }
    }

    private void readEmailFileAndWait(int recId) {
        SparseArray<File> files = ((PbrRecord) this.mPbrRecords.get(recId)).mFileIds;
        if (files != null) {
            File email = (File) files.get(USIM_EFEMAIL_TAG);
            if (email != null) {
                if (email.getParentTag() == 169) {
                    if (files.get(193) == null) {
                        Rlog.e(LOG_TAG, "Can't locate EF_IAP in EF_PBR.");
                        return;
                    }
                    log("EF_IAP exists. Loading EF_IAP to retrieve the index.");
                    readIapFileAndWait(((File) files.get(193)).getEfid());
                    if (this.mIapFileRecord == null) {
                        Rlog.e(LOG_TAG, "Error: IAP file is empty");
                        return;
                    }
                    log("EF_EMAIL order in PBR record: " + email.getIndex());
                }
                int emailEfid = email.getEfid();
                log("EF_EMAIL exists in PBR. efid = 0x" + Integer.toHexString(emailEfid).toUpperCase());
                for (int i = 0; i < recId; i++) {
                    if (this.mPbrRecords.get(i) != null) {
                        SparseArray<File> previousFileIds = ((PbrRecord) this.mPbrRecords.get(i)).mFileIds;
                        if (previousFileIds != null) {
                            File id = (File) previousFileIds.get(USIM_EFEMAIL_TAG);
                            if (id != null && id.getEfid() == emailEfid) {
                                log("Skipped this EF_EMAIL which was loaded earlier");
                                return;
                            }
                        }
                        continue;
                    }
                }
                this.mFh.loadEFLinearFixedAll(emailEfid, obtainMessage(4));
                try {
                    this.mLock.wait();
                } catch (InterruptedException e) {
                    Rlog.e(LOG_TAG, "Interrupted Exception in readEmailFileAndWait");
                }
                if (this.mEmailFileRecord == null) {
                    Rlog.e(LOG_TAG, "Error: Email file is empty");
                } else if (email.getParentTag() != 169 || this.mIapFileRecord == null) {
                    buildType1EmailList(recId);
                } else {
                    buildType2EmailList(recId);
                }
            }
        }
    }

    private void buildType1EmailList(int recId) {
        if (this.mPbrRecords.get(recId) != null) {
            int numRecs = ((PbrRecord) this.mPbrRecords.get(recId)).mMasterFileRecordNum;
            log("Building type 1 email list. recId = " + recId + ", numRecs = " + numRecs);
            int i = 0;
            while (i < numRecs) {
                try {
                    byte[] emailRec = (byte[]) this.mEmailFileRecord.get(i);
                    int sfi = emailRec[emailRec.length - 2];
                    int adnRecId = emailRec[emailRec.length - 1];
                    String email = readEmailRecord(i);
                    if (!(email == null || email.equals(""))) {
                        int adnEfid;
                        if (sfi == -1 || this.mSfiEfidTable.get(sfi) == 0) {
                            File file = (File) ((PbrRecord) this.mPbrRecords.get(recId)).mFileIds.get(192);
                            if (file != null) {
                                adnEfid = file.getEfid();
                            }
                        } else {
                            adnEfid = this.mSfiEfidTable.get(sfi);
                        }
                        int index = ((65535 & adnEfid) << 8) | ((adnRecId - 1) & 255);
                        ArrayList<String> emailList = (ArrayList) this.mEmailsForAdnRec.get(index);
                        if (emailList == null) {
                            emailList = new ArrayList();
                        }
                        log("Adding email #" + i + " list to index 0x" + Integer.toHexString(index).toUpperCase());
                        emailList.add(email);
                        this.mEmailsForAdnRec.put(index, emailList);
                    }
                    i++;
                } catch (IndexOutOfBoundsException e) {
                    Rlog.e(LOG_TAG, "Error: Improper ICC card: No email record for ADN, continuing");
                }
            }
        }
    }

    private boolean buildType2EmailList(int recId) {
        if (this.mPbrRecords.get(recId) == null) {
            return false;
        }
        int numRecs = ((PbrRecord) this.mPbrRecords.get(recId)).mMasterFileRecordNum;
        log("Building type 2 email list. recId = " + recId + ", numRecs = " + numRecs);
        File adnFile = (File) ((PbrRecord) this.mPbrRecords.get(recId)).mFileIds.get(192);
        if (adnFile == null) {
            Rlog.e(LOG_TAG, "Error: Improper ICC card: EF_ADN does not exist in PBR files");
            return false;
        }
        int adnEfid = adnFile.getEfid();
        for (int i = 0; i < numRecs; i++) {
            try {
                String email = readEmailRecord(((byte[]) this.mIapFileRecord.get(i))[((File) ((PbrRecord) this.mPbrRecords.get(recId)).mFileIds.get(USIM_EFEMAIL_TAG)).getIndex()] - 1);
                if (!(email == null || (email.equals("") ^ 1) == 0)) {
                    int index = ((65535 & adnEfid) << 8) | (i & 255);
                    ArrayList<String> emailList = (ArrayList) this.mEmailsForAdnRec.get(index);
                    if (emailList == null) {
                        emailList = new ArrayList();
                    }
                    emailList.add(email);
                    log("Adding email list to index 0x" + Integer.toHexString(index).toUpperCase());
                    this.mEmailsForAdnRec.put(index, emailList);
                }
            } catch (IndexOutOfBoundsException e) {
                Rlog.e(LOG_TAG, "Error: Improper ICC card: Corrupted EF_IAP");
            }
        }
        return true;
    }

    private void readIapFileAndWait(int efid) {
        this.mFh.loadEFLinearFixedAll(efid, obtainMessage(3));
        try {
            this.mLock.wait();
        } catch (InterruptedException e) {
            Rlog.e(LOG_TAG, "Interrupted Exception in readIapFileAndWait");
        }
    }

    private void updatePhoneAdnRecord() {
        int numAdnRecs = this.mPhoneBookRecords.size();
        for (int i = 0; i < numAdnRecs; i++) {
            AdnRecord rec = (AdnRecord) this.mPhoneBookRecords.get(i);
            try {
                ArrayList<String> emailList = (ArrayList) this.mEmailsForAdnRec.get(((65535 & rec.getEfid()) << 8) | ((rec.getRecId() - 1) & 255));
                if (emailList != null) {
                    String[] emails = new String[emailList.size()];
                    System.arraycopy(emailList.toArray(), 0, emails, 0, emailList.size());
                    rec.setEmails(emails);
                    log("Adding email list to ADN (0x" + Integer.toHexString(((AdnRecord) this.mPhoneBookRecords.get(i)).getEfid()).toUpperCase() + ") record #" + ((AdnRecord) this.mPhoneBookRecords.get(i)).getRecId());
                    this.mPhoneBookRecords.set(i, rec);
                }
            } catch (IndexOutOfBoundsException e) {
            }
        }
    }

    private String readEmailRecord(int recId) {
        try {
            byte[] emailRec = (byte[]) this.mEmailFileRecord.get(recId);
            return IccUtils.adnStringFieldToString(emailRec, 0, emailRec.length - 2);
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    @VivoHook(hookType = VivoHookType.CHANGE_CODE)
    private void readAdnFileAndWait(int recId) {
        SparseArray<File> files = ((PbrRecord) this.mPbrRecords.get(recId)).mFileIds;
        if (files != null && files.size() != 0) {
            int extEf = 0;
            if (files.get(194) != null) {
                extEf = ((File) files.get(194)).getEfid();
            }
            if (files.get(192) != null) {
                int previousSize = this.mPhoneBookRecords.size();
                this.mAdnCache.requestLoadAllAdnLike(((File) files.get(192)).getEfid(), extEf, obtainMessage(2, Integer.valueOf(recId)));
                try {
                    this.mLock.wait();
                } catch (InterruptedException e) {
                    Rlog.e(LOG_TAG, "Interrupted Exception in readAdnFileAndWait");
                }
                ((PbrRecord) this.mPbrRecords.get(recId)).mMasterFileRecordNum = this.mPhoneBookRecords.size() - previousSize;
            }
        }
    }

    private void createPbrFile(ArrayList<byte[]> records) {
        if (records == null) {
            this.mPbrRecords = null;
            this.mIsPbrPresent = Boolean.valueOf(false);
            return;
        }
        this.mPbrRecords = new ArrayList();
        for (int i = 0; i < records.size(); i++) {
            if (((byte[]) records.get(i))[0] != INVALID_BYTE) {
                this.mPbrRecords.add(new PbrRecord((byte[]) records.get(i)));
            }
        }
        for (PbrRecord record : this.mPbrRecords) {
            File file = (File) record.mFileIds.get(192);
            if (file != null) {
                int sfi = file.getSfi();
                if (sfi != -1) {
                    this.mSfiEfidTable.put(sfi, ((File) record.mFileIds.get(192)).getEfid());
                }
            }
        }
    }

    @VivoHook(hookType = VivoHookType.CHANGE_CODE)
    public void handleMessage(Message msg) {
        AsyncResult ar;
        Object obj;
        switch (msg.what) {
            case 1:
                log("Loading PBR done");
                ar = msg.obj;
                if (ar.exception == null) {
                    createPbrFile((ArrayList) ar.result);
                }
                obj = this.mLock;
                synchronized (obj) {
                    this.mLock.notify();
                    break;
                }
            case 2:
                ar = (AsyncResult) msg.obj;
                int pbrIndex = ((Integer) ar.userObj).intValue();
                if (ar.exception == null) {
                    if (pbrIndex > 0) {
                        updateAdnsRecordnum(ar);
                    } else {
                        this.mPhoneBookRecords.addAll((ArrayList) ar.result);
                    }
                }
                obj = this.mLock;
                synchronized (obj) {
                    this.mLock.notify();
                    break;
                }
            case 3:
                log("Loading USIM IAP records done");
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    this.mIapFileRecord = (ArrayList) ar.result;
                }
                obj = this.mLock;
                synchronized (obj) {
                    this.mLock.notify();
                    break;
                }
            case 4:
                log("Loading USIM Email records done");
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    this.mEmailFileRecord = (ArrayList) ar.result;
                }
                obj = this.mLock;
                synchronized (obj) {
                    this.mLock.notify();
                    break;
                }
            default:
                return;
        }
    }

    private void log(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    @VivoHook(hookType = VivoHookType.NEW_METHOD)
    public int getUsimAdnCount() {
        if (this.mPhoneBookRecords == null || (this.mPhoneBookRecords.isEmpty() ^ 1) == 0) {
            return 0;
        }
        log("getUsimAdnCount count is" + this.mPhoneBookRecords.size());
        return this.mPhoneBookRecords.size();
    }

    @VivoHook(hookType = VivoHookType.NEW_METHOD)
    private void updateAdnsRecordnum(AsyncResult ar) {
        int pbrIndex = ((Integer) ar.userObj).intValue();
        ArrayList<AdnRecord> tempRecord = ar.result;
        int nOffset = pbrIndex * ((PbrRecord) this.mPbrRecords.get(pbrIndex - 1)).mMasterFileRecordNum;
        log("updateAdnsRecordnum pbrIndex = " + pbrIndex + " , nOffset = " + nOffset);
        if (tempRecord != null) {
            int numAdnRecs = tempRecord.size();
            int i = 0;
            while (i < numAdnRecs) {
                try {
                    AdnRecord rec = (AdnRecord) tempRecord.get(i);
                    rec.setRecordNumber(rec.getRecordNumber() + nOffset);
                    i++;
                } catch (IndexOutOfBoundsException e) {
                    log("UsimPhoneBookManager readType1Ef: mPhoneBookRecords IndexOutOfBoundsException numAdnRecs is " + numAdnRecs + "index is " + i);
                }
            }
            this.mPhoneBookRecords.addAll(tempRecord);
        }
    }
}
