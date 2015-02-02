package jd.plugins.download;

import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

import jd.controlling.downloadcontroller.DiskSpaceManager.DISKSPACERESERVATIONRESULT;
import jd.controlling.downloadcontroller.DiskSpaceReservation;
import jd.controlling.downloadcontroller.DownloadWatchDog;
import jd.controlling.downloadcontroller.ExceptionRunnable;
import jd.controlling.downloadcontroller.FileIsLockedException;
import jd.controlling.downloadcontroller.ManagedThrottledConnectionHandler;
import jd.controlling.downloadcontroller.SingleDownloadController;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.DownloadLinkDatabindingInterface;
import jd.plugins.FilePackage;
import jd.plugins.Plugin;
import jd.plugins.PluginForHost;
import jd.plugins.PluginProgress;
import jd.plugins.download.HashInfo.TYPE;

import org.appwork.utils.IO;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.HexFormatter;
import org.appwork.utils.logging2.LogSource;
import org.jdownloader.controlling.FileCreationManager;
import org.jdownloader.plugins.FinalLinkState;
import org.jdownloader.plugins.HashCheckPluginProgress;
import org.jdownloader.plugins.SkipReason;
import org.jdownloader.plugins.SkipReasonException;

public class DownloadLinkDownloadable implements Downloadable {
    /**
     *
     */

    private final DownloadLink downloadLink;
    private PluginForHost      plugin;

    public DownloadLinkDownloadable(DownloadLink downloadLink) {
        this.downloadLink = downloadLink;
        plugin = downloadLink.getLivePlugin();
    }

    @Override
    public void setResumeable(boolean value) {
        downloadLink.setResumeable(value);
    }

    @Override
    public Browser getContextBrowser() {
        return plugin.getBrowser().cloneBrowser();

    }

    @Override
    public String getMD5Hash() {
        return downloadLink.getMD5Hash();
    }

    @Override
    public String getSha1Hash() {
        return downloadLink.getSha1Hash();
    }

    @Override
    public long[] getChunksProgress() {
        return downloadLink.getView().getChunksProgress();
    }

    @Override
    public void setChunksProgress(long[] ls) {
        downloadLink.setChunksProgress(ls);
    }

    @Override
    public Logger getLogger() {
        return plugin.getLogger();
    }

    @Override
    public void setDownloadInterface(DownloadInterface di) {
        plugin.setDownloadInterface(di);
    }

    @Override
    public long getVerifiedFileSize() {
        return downloadLink.getView().getBytesTotalVerified();
    }

    @Override
    public boolean isServerComaptibleForByteRangeRequest() {
        return downloadLink.getBooleanProperty("ServerComaptibleForByteRangeRequest", false);
    }

    @Override
    public String getHost() {
        return downloadLink.getHost();
    }

    @Override
    public boolean isDebug() {
        return this.plugin.getBrowser().isDebug();
    }

    @Override
    public void setDownloadTotalBytes(long l) {
        downloadLink.setDownloadSize(l);
    }

    public SingleDownloadController getDownloadLinkController() {
        return downloadLink.getDownloadLinkController();
    }

    @Override
    public void setLinkStatus(int finished) {
        getDownloadLinkController().getLinkStatus().setStatus(finished);
    }

    @Override
    public void setVerifiedFileSize(long length) {
        downloadLink.setVerifiedFileSize(length);
    }

    @Override
    public void validateLastChallengeResponse() {
        plugin.validateLastChallengeResponse();
    }

    @Override
    public void setConnectionHandler(ManagedThrottledConnectionHandler managedConnetionHandler) {
        getDownloadLinkController().getConnectionHandler().addConnectionHandler(managedConnetionHandler);
    }

    @Override
    public void removeConnectionHandler(ManagedThrottledConnectionHandler managedConnetionHandler) {
        getDownloadLinkController().getConnectionHandler().removeConnectionHandler(managedConnetionHandler);
    }

    @Override
    public void setAvailable(AvailableStatus status) {
        downloadLink.setAvailableStatus(status);
    }

    @Override
    public String getFinalFileName() {
        return downloadLink.getFinalFileName();
    }

    @Override
    public void setFinalFileName(String newfinalFileName) {
        downloadLink.setFinalFileName(newfinalFileName);
    }

    @Override
    public boolean checkIfWeCanWrite(final ExceptionRunnable runOkay, final ExceptionRunnable runFailed) throws Exception {
        final SingleDownloadController dlc = getDownloadLinkController();
        final AtomicBoolean atomicBoolean = new AtomicBoolean(false);
        DownloadWatchDog.getInstance().localFileCheck(dlc, new ExceptionRunnable() {

            @Override
            public void run() throws Exception {
                runOkay.run();
                atomicBoolean.set(true);
            }
        }, runFailed);
        return atomicBoolean.get();
    }

    @Override
    public void lockFiles(File... files) throws FileIsLockedException {
        final SingleDownloadController dlc = getDownloadLinkController();
        for (File f : files) {
            dlc.lockFile(f);
        }

    }

    @Override
    public void unlockFiles(File... files) {
        final SingleDownloadController dlc = getDownloadLinkController();
        for (File f : files) {
            dlc.unlockFile(f);
        }
    }

    @Override
    public void addDownloadTime(long ms) {
        downloadLink.addDownloadTime(ms);
    }

    @Override
    public void setLinkStatusText(String label) {
        getDownloadLinkController().getLinkStatus().setStatusText(label);
    }

    @Override
    public long getDownloadTotalBytes() {
        return downloadLink.getView().getBytesTotalEstimated();
    }

    @Override
    public void setDownloadBytesLoaded(long bytes) {
        downloadLink.setDownloadCurrent(bytes);
    }

    @Override
    public boolean isHashCheckEnabled() {
        return downloadLink.getBooleanProperty("ALLOW_HASHCHECK", true);
    }

    @Override
    public String getName() {
        return downloadLink.getName();
    }

    @Override
    public long getKnownDownloadSize() {
        return downloadLink.getView().getBytesTotal();
    }

    @Override
    public void addPluginProgress(PluginProgress progress) {
        downloadLink.addPluginProgress(progress);
    }

    public HashResult getHashResult(HashInfo hashInfo, File outputPartFile) {
        if (hashInfo == null) {
            return null;
        }
        TYPE type = hashInfo.getType();
        final PluginProgress hashProgress = new HashCheckPluginProgress(outputPartFile, Color.YELLOW.darker(), type);
        hashProgress.setProgressSource(this);
        try {
            addPluginProgress(hashProgress);
            final byte[] b = new byte[32767];
            String hashFile = null;
            FileInputStream fis = null;
            int n = 0;
            int cur = 0;
            switch (type) {
            case MD5:
            case SHA1:
                DigestInputStream is = null;
                try {
                    is = new DigestInputStream(fis = new FileInputStream(outputPartFile), MessageDigest.getInstance(type.name()));
                    while ((n = is.read(b)) >= 0) {
                        cur += n;
                        hashProgress.setCurrent(cur);
                    }
                    hashFile = HexFormatter.byteArrayToHex(is.getMessageDigest().digest());
                } catch (final Throwable e) {
                    LogSource.exception(getLogger(), e);
                } finally {
                    try {
                        is.close();
                    } catch (final Throwable e) {
                    }
                    try {
                        fis.close();
                    } catch (final Throwable e) {
                    }
                }
                break;
            case CRC32:
                CheckedInputStream cis = null;
                try {
                    fis = new FileInputStream(outputPartFile);
                    cis = new CheckedInputStream(fis, new CRC32());
                    while ((n = cis.read(b)) >= 0) {
                        cur += n;
                        hashProgress.setCurrent(cur);
                    }
                    long value = cis.getChecksum().getValue();
                    byte[] longBytes = new byte[] { (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value };
                    hashFile = HexFormatter.byteArrayToHex(longBytes);
                } catch (final Throwable e) {
                    LogSource.exception(getLogger(), e);
                } finally {
                    try {
                        cis.close();
                    } catch (final Throwable e) {
                    }
                    try {
                        fis.close();
                    } catch (final Throwable e) {
                    }
                }
                break;
            }
            return new HashResult(hashInfo, hashFile);
        } finally {
            removePluginProgress(hashProgress);
        }
    }

    @Override
    public HashInfo getHashInfo() {
        String hash;
        // StatsManager
        String name = getName();
        if ((hash = downloadLink.getMD5Hash()) != null && hash.matches("^[a-fA-F0-9]{32}$")) {
            /* MD5 Check */
            return new HashInfo(hash, HashInfo.TYPE.MD5);
        } else if (!StringUtils.isEmpty(hash = downloadLink.getSha1Hash()) && hash.matches("^[a-fA-F0-9]{40}$")) {
            /* SHA1 Check */
            return new HashInfo(hash, HashInfo.TYPE.SHA1);
        } else if ((hash = new Regex(name, ".*?\\[([A-Fa-f0-9]{8})\\]").getMatch(0)) != null) {
            return new HashInfo(hash, HashInfo.TYPE.CRC32, false);
        } else {
            FilePackage filePackage = downloadLink.getFilePackage();
            if (!FilePackage.isDefaultFilePackage(filePackage)) {
                ArrayList<DownloadLink> SFVs = new ArrayList<DownloadLink>();
                boolean readL = filePackage.getModifyLock().readLock();
                try {
                    for (DownloadLink dl : filePackage.getChildren()) {
                        if (dl != downloadLink && FinalLinkState.CheckFinished(dl.getFinalLinkState()) && dl.getFileOutput().toLowerCase().endsWith(".sfv")) {
                            SFVs.add(dl);
                        }
                    }
                } finally {
                    filePackage.getModifyLock().readUnlock(readL);
                }
                /* SFV File Available, lets use it */
                for (DownloadLink SFV : SFVs) {
                    File file = getFileOutput(SFV, false);
                    if (file.exists()) {
                        String sfvText;
                        try {
                            sfvText = IO.readFileToString(file);
                            if (sfvText != null) {
                                /* Delete comments */
                                sfvText = sfvText.replaceAll(";(.*?)[\r\n]{1,2}", "");
                                if (sfvText != null && sfvText.contains(name)) {
                                    hash = new Regex(sfvText, Pattern.quote(name) + "\\s*([A-Fa-f0-9]{8})").getMatch(0);
                                    if (hash != null) {
                                        return new HashInfo(hash, HashInfo.TYPE.CRC32);
                                    }
                                }
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
        return null;
    }

    private File getFileOutput(DownloadLink link, boolean ignoreCustom) {
        final SingleDownloadController controller = link.getDownloadLinkController();
        if (controller == null) {
            return new File(link.getFileOutput(false, ignoreCustom));
        } else {
            return controller.getFileOutput(false, ignoreCustom);
        }

    }

    @Override
    public void setHashResult(HashResult result) {
        getDownloadLinkController().setHashResult(result);
    }

    @Override
    public boolean rename(final File outputPartFile, final File outputCompleteFile) throws InterruptedException {
        boolean renameOkay = false;
        int retry = 5;
        /* rename part file to final filename */
        while (retry > 0) {
            /* first we try normal rename method */
            if ((renameOkay = outputPartFile.renameTo(outputCompleteFile)) == true) {
                break;
            }
            /* this may fail because something might lock the file */
            Thread.sleep(1000);
            retry--;
        }
        /* Fallback */
        if (renameOkay == false) {
            /* rename failed, lets try fallback */
            getLogger().severe("Could not rename file " + outputPartFile + " to " + outputCompleteFile);
            getLogger().severe("Try copy workaround!");
            DiskSpaceReservation reservation = new DiskSpaceReservation() {

                @Override
                public long getSize() {
                    return outputPartFile.length() - outputCompleteFile.length();
                }

                @Override
                public File getDestination() {
                    return outputCompleteFile;
                }
            };
            try {
                try {
                    DISKSPACERESERVATIONRESULT result = DownloadWatchDog.getInstance().getSession().getDiskSpaceManager().checkAndReserve(reservation, this);
                    switch (result) {
                    case OK:
                    case UNSUPPORTED:
                        IO.copyFile(outputPartFile, outputCompleteFile);
                        renameOkay = true;
                        outputPartFile.delete();
                        break;
                    }
                } finally {
                    DownloadWatchDog.getInstance().getSession().getDiskSpaceManager().free(reservation, this);
                }
            } catch (Throwable e) {
                LogSource.exception(getLogger(), e);
                /* error happened, lets delete complete file */
                if (outputCompleteFile.exists() && outputCompleteFile.length() != outputPartFile.length()) {
                    FileCreationManager.getInstance().delete(outputCompleteFile, null);
                }
            }
            if (!renameOkay) {
                getLogger().severe("Copy workaround: :(");
            } else {
                getLogger().severe("Copy workaround: :)");
            }
        }

        return renameOkay;
    }

    @Override
    public void waitForNextConnectionAllowed() throws InterruptedException {
        plugin.waitForNextConnectionAllowed(downloadLink);
    }

    @Override
    public boolean isInterrupted() {
        final SingleDownloadController sdc = getDownloadLinkController();
        return (sdc != null && sdc.isAborting());
    }

    @Override
    public String getFileOutput() {
        return getFileOutput(downloadLink, false).getAbsolutePath();
    }

    @Override
    public int getLinkStatus() {
        return getDownloadLinkController().getLinkStatus().getStatus();
    }

    @Override
    public String getFileOutputPart() {
        return getFileOutput() + ".part";
    }

    @Override
    public String getFinalFileOutput() {
        return getFileOutput(downloadLink, true).getAbsolutePath();
    }

    @Override
    public boolean isResumable() {
        return downloadLink.isResumeable();
    }

    @Override
    public DiskSpaceReservation createDiskSpaceReservation() {
        return new DiskSpaceReservation() {

            @Override
            public long getSize() {
                final File partFile = new File(getFileOutputPart());
                long doneSize = Math.max((partFile.exists() ? partFile.length() : 0l), getDownloadBytesLoaded());
                return getKnownDownloadSize() - Math.max(0, doneSize);
            }

            @Override
            public File getDestination() {
                return new File(getFileOutput());
            }
        };
    }

    @Override
    public void checkAndReserve(DiskSpaceReservation reservation) throws Exception {
        DISKSPACERESERVATIONRESULT result = DownloadWatchDog.getInstance().getSession().getDiskSpaceManager().checkAndReserve(reservation, getDownloadLinkController());
        switch (result) {
        case FAILED:
            throw new SkipReasonException(SkipReason.DISK_FULL);
        case INVALIDDESTINATION:
            throw new SkipReasonException(SkipReason.INVALID_DESTINATION);
        }
    }

    @Override
    public void free(DiskSpaceReservation reservation) {
        DownloadWatchDog.getInstance().getSession().getDiskSpaceManager().free(reservation, getDownloadLinkController());
    }

    @Override
    public long getDownloadBytesLoaded() {
        return downloadLink.getView().getBytesLoaded();
    }

    @Override
    public boolean removePluginProgress(PluginProgress remove) {
        return downloadLink.removePluginProgress(remove);
    }

    @Override
    public <T> T getDataBindingInterface(Class<? extends DownloadLinkDatabindingInterface> T) {
        return (T) downloadLink.bindData(T);
    }

    @Override
    public void updateFinalFileName() {
        if (getFinalFileName() == null) {
            Logger logger = getLogger();
            DownloadInterface dl = getDownloadInterface();
            URLConnectionAdapter connection = getDownloadInterface().getConnection();
            logger.info("FinalFileName is not set yet!");
            if (connection.isContentDisposition() || dl.allowFilenameFromURL) {
                String name = Plugin.getFileNameFromHeader(connection);
                logger.info("FinalFileName: set to " + name + "(from connection)");
                if (dl.fixWrongContentDispositionHeader) {
                    setFinalFileName(Encoding.htmlDecode(name));
                } else {
                    setFinalFileName(name);
                }
            } else {
                String name = getName();
                logger.info("FinalFileName: set to " + name + "(from plugin)");
                setFinalFileName(name);
            }
        }
    }

    @Override
    public DownloadInterface getDownloadInterface() {
        return plugin.getDownloadInterface();
    }

    public void setHashInfo(HashInfo hashInfo) {
        if (hashInfo != null && hashInfo.isTrustworthy() && getHashInfo() == null) {
            switch (hashInfo.getType()) {
            case MD5:
                downloadLink.setMD5Hash(hashInfo.getHash());
                break;
            case SHA1:
                downloadLink.setSha1Hash(hashInfo.getHash());
                break;
            }
        }
    }

    @Override
    public int getChunks() {
        return downloadLink.getChunks();
    }
}