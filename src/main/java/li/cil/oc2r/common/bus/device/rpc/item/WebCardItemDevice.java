/* SPDX-License-Identifier: MIT */

package li.cil.oc2r.common.bus.device.rpc.item;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import li.cil.oc2r.api.bus.device.object.Callback;
import li.cil.oc2r.api.bus.device.object.DocumentedDevice;
import li.cil.oc2r.api.bus.device.object.Parameter;
import li.cil.oc2r.api.capabilities.TerminalUserProvider;
import li.cil.oc2r.common.Constants;
import li.cil.oc2r.common.network.Network;
import li.cil.oc2r.common.network.message.ExportedFileMessage;
import li.cil.oc2r.common.network.message.ServerCanceledImportFileMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class WebCardItemDevice extends AbstractItemRPCDevice implements DocumentedDevice {
    public static final int MAX_TRANSFERRED_FILE_SIZE = Constants.MEGABYTE;

    private static final String BEGIN_EXPORT_FILE = "beginExportFile";
    private static final String WRITE_EXPORT_FILE = "writeExportFile";
    private static final String FINISH_EXPORT_FILE = "finishExportFile";
    private static final String REQUEST_IMPORT_FILE = "requestImportFile";
    private static final String BEGIN_IMPORT_FILE = "beginImportFile";
    private static final String READ_IMPORT_FILE = "readImportFile";
    private static final String RESET = "reset";
    private static final String NAME = "name";
    private static final String DATA = "data";

    ///////////////////////////////////////////////////////////////////

    private enum State {
        IDLE,
        EXPORTING,
        IMPORT_REQUESTED,
        IMPORTING,
        IMPORT_CANCELED,
    }

    private static final class ExportedFile {
        public final String name;
        public final ByteArrayOutputStream data = new ByteArrayOutputStream();

        private ExportedFile(final String name) {
            this.name = name;
        }
    }

    private static final class ImportedFile {
        public final String url;
        public final String name;
//        public final int size;
        public final ByteArrayInputStream data;

        private ImportedFile(final String url, final String name, final byte[] data) {
            this.url = url;
            this.name = name;
//            this.size = data.length;
            this.data = new ByteArrayInputStream(data);
        }
    }

    public record ImportedFileInfo(String url, String name, ByteArrayInputStream size) { }

    private static final class ImportFileRequest {
        public final Set<ServerPlayer> PendingPlayers = Collections.newSetFromMap(new WeakHashMap<>());
        public final WeakReference<WebCardItemDevice> Device;

        private ImportFileRequest(final WebCardItemDevice device) {
            Device = new WeakReference<>(device);
        }
    }

    ///////////////////////////////////////////////////////////////////

    private static final Int2ObjectArrayMap<ImportFileRequest> importingDevices = new Int2ObjectArrayMap<>();
    private static int nextImportId = 1;

    private final TerminalUserProvider userProvider;
    private State state;
    private ExportedFile exportedFile;
    private int importingId;
    private ImportedFile importedFile;

    ///////////////////////////////////////////////////////////////////

    public WebCardItemDevice(final ItemStack identity, final TerminalUserProvider userProvider) {
        super(identity, "web_card");
        this.userProvider = userProvider;
    }

    ///////////////////////////////////////////////////////////////////

    public static void setImportedFile(final int id,final String url, final String name, final byte[] data) {
        synchronized (importingDevices) {
            final ImportFileRequest request = importingDevices.remove(id);
            if (request != null) {
                final WebCardItemDevice device = request.Device.get();
                if (device != null) {
                    device.importedFile = new ImportedFile(url, name, data);
                    final ServerCanceledImportFileMessage message = new ServerCanceledImportFileMessage(id);
                    for (final ServerPlayer serverPlayer : request.PendingPlayers) {
                        Network.sendToClient(message, serverPlayer);
                    }
                }
            }
        }
    }

    public static void cancelImport(final ServerPlayer player, final int id) {
        synchronized (importingDevices) {
            final ImportFileRequest request = importingDevices.get(id);
            if (request != null) {
                request.PendingPlayers.remove(player);
                if (request.PendingPlayers.isEmpty()) {
                    importingDevices.remove(id);
                    final WebCardItemDevice device = request.Device.get();
                    if (device != null) {
                        device.state = State.IMPORT_CANCELED;
                    }
                }
            }
        }
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    public void unmount() {
        reset();
    }

    @Callback(name = BEGIN_EXPORT_FILE, synchronize = false)
    public void beginExportFile(@Parameter(NAME) final String name) {
        if (state != State.IDLE) {
            throw new IllegalStateException("invalid state");
        }

        if (StringUtil.isNullOrEmpty(name)) {
            throw new IllegalArgumentException("name must not be empty");
        }

        state = State.EXPORTING;
        exportedFile = new ExportedFile(name);
    }

    @Callback(name = WRITE_EXPORT_FILE, synchronize = false)
    public void writeExportFile(@Parameter(DATA) @Nullable final byte[] data) throws IOException {
        if (state != State.EXPORTING) {
            throw new IllegalStateException("invalid state");
        }

        if (data == null) {
            throw new IllegalArgumentException("data is required");
        }

        exportedFile.data.write(data);

        if (exportedFile.data.size() > MAX_TRANSFERRED_FILE_SIZE) {
            reset();
            throw new IllegalArgumentException("exported file too large");
        }
    }

    @Callback(name = FINISH_EXPORT_FILE)
    public void finishExportFile() {
        if (state != State.EXPORTING) {
            throw new IllegalStateException("invalid state");
        }

        try {
            for (final Player player : userProvider.getTerminalUsers()) {
                if (player instanceof final ServerPlayer serverPlayer) {
                    final ExportedFileMessage message = new ExportedFileMessage(exportedFile.name, exportedFile.data.toByteArray());
                    Network.sendToClient(message, serverPlayer);
                }
            }
        } finally {
            reset();
        }
    }

    @Callback(name = REQUEST_IMPORT_FILE)
    public boolean requestImportFile(@Parameter("url") String external_url, @Parameter("file")  String file_name) {
        if (state != State.IDLE) {
            throw new IllegalStateException("invalid state");
        }

        final ArrayList<ServerPlayer> players = new ArrayList<>();
        for (final Player player : userProvider.getTerminalUsers()) {
            if (player instanceof final ServerPlayer serverPlayer) {
                players.add(serverPlayer);
            }
        }

        if (players.isEmpty()) {
            return false;
        }

        state = State.IMPORT_REQUESTED;
//        importingId = nextImportId++;
        synchronized (importingDevices) {

            for (final ServerPlayer serverPlayer : players) {
                try {
                    InputStream in = new URL(external_url).openStream();
                    Files.copy(in, Paths.get(file_name), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Received from command request of external url :  " + external_url);
                    System.out.println("Received from command request for file name :  " + file_name);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
//            importingDevices.put(importingId, new ImportFileRequest(this));
        }
//            final RequestImportedFileMessage message = new RequestImportedFileMessage(importingId);
//            Network.sendToClient(message, serverPlayer);
        }

        return true;
    }

    @Nullable
    @Callback(name = BEGIN_IMPORT_FILE)
    public ImportedFileInfo beginImportFile() {
        if (state == State.IMPORT_CANCELED) {
            reset();
            throw new IllegalStateException("import was canceled");
        }

        if (state != State.IMPORT_REQUESTED) {
            throw new IllegalStateException("invalid state");
        }

        if (importedFile == null) {
            System.out.println("Received from command request of all as null from beginImportFile :  " + importedFile);
            return null;
        }

        state = State.IMPORTING;
        System.out.println("Received from command request of external url from beginImportFile :  " + importedFile.url);
        System.out.println("Received from command request for file name from beginImportFile :  " + importedFile.name);
//        System.out.println("Received from command request for file size from beginImportFile :  " + importedFile.size);
        return new ImportedFileInfo(importedFile.url, importedFile.name, importedFile.data);
    }

    @Nullable
    @Callback(name = READ_IMPORT_FILE)
    public byte[] readImportFile() throws IOException {
        if (state == State.IMPORT_CANCELED) {
            reset();
            throw new IllegalStateException("import was canceled");
        }

        if (state != State.IMPORTING) {
            throw new IllegalStateException("invalid state");
        }

        if (importedFile == null) {
            System.out.println("Received from command request of all as null from readImportFile :  " + importedFile);
            return new byte[0];
        }
        System.out.println("Received from command request of external url from beginImportFile :  " + importedFile.url);
        System.out.println("Received from command request for file name from beginImportFile :  " + importedFile.name);
//        System.out.println("Received from command request for file size from beginImportFile :  " + importedFile.size);

        final byte[] buffer = new byte[512];
//        final int count = importedFile.data.read(buffer);
//        if (count <= 0) {
//            reset();
//            return null;
//        }
//        if (count < buffer.length) {
//            final byte[] data = new byte[count];
//            System.arraycopy(buffer, 0, data, 0, count);
//            return data;
//        } else {
            return buffer;
//        }
    }

    @Callback(name = RESET)
    public void reset() {
        state = State.IDLE;
        exportedFile = null;
        importedFile = null;
        synchronized (importingDevices) {
            importingDevices.remove(importingId);
        }
    }

    @Override
    public void getDeviceDocumentation(final DeviceVisitor visitor) {
        visitor.visitCallback(BEGIN_EXPORT_FILE)
            .description("Begins exporting a file to external data storage. Requires calls to " +
                WRITE_EXPORT_FILE + "() to provide data of the exported file and a call " +
                "to " + FINISH_EXPORT_FILE + "() to complete the export.\n" +
                "This method may error if the device is currently exporting or importing.")
            .parameterDescription(NAME, "the name of the file being exported.");
        visitor.visitCallback(WRITE_EXPORT_FILE)
            .description("""
                Appends more data to the currently being exported file.
                This method may error if the device is not currently exporting or the \
                export was interrupted.
                """)
            .parameterDescription(DATA, "the contents of the file being exported.");
        visitor.visitCallback(FINISH_EXPORT_FILE)
            .description("Finishes an export. This will prompt present users to select an external " +
                "file location for the file being exported. If multiple users are present, " +
                "the file is provided to all users.\n" +
                "This method may error if the device is not currently exporting or the " +
                "export was interrupted.");
        visitor.visitCallback(BEGIN_IMPORT_FILE)
            .description("Begins a file import operation. This will prompt present users to select " +
                "an externally stored file for import. If multiple users are present, the " +
                "first user to select a file will have their file uploaded. Use the " +
                READ_IMPORT_FILE + "() method to read the contents of the file being imported.\n" +
                "This method may error if the device is currently exporting or importing.");
        visitor.visitCallback(READ_IMPORT_FILE)
            .description("Tries to read some data from a file being imported. Returns zero length " +
                "data if no data is available yet. Returns null when no more data is " +
                "available.\n" +
                "This method may error if the device is not currently importing or the " +
                "import was interrupted.")
            .returnValueDescription("data from the file being imported.");
        visitor.visitCallback(RESET)
            .description("Resets the device and cancels any currently running export or import operation.");
    }
}
///* SPDX-License-Identifier: MIT */
//
//package li.cil.oc2r.common.bus.device.rpc.item;
//
//import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
//import li.cil.oc2r.api.bus.device.object.Callback;
//import li.cil.oc2r.api.bus.device.object.DocumentedDevice;
//import li.cil.oc2r.api.bus.device.object.Parameter;
//import li.cil.oc2r.api.capabilities.TerminalUserProvider;
//import net.minecraft.server.level.ServerPlayer;
//import net.minecraft.world.entity.player.Player;
//import net.minecraft.world.item.ItemStack;
//
//import javax.annotation.Nullable;
//import java.io.*;
//import java.lang.ref.WeakReference;
//import java.net.URL;
//import java.nio.file.Files;
//import java.nio.file.Paths;
//import java.nio.file.StandardCopyOption;
//import java.util.ArrayList;
//
//public final class WebCardItemDevice extends AbstractItemRPCDevice implements DocumentedDevice {
//
//    private static final String REQUEST_IMPORT_FILE = "requestImportFile";
//    private static final String BEGIN_IMPORT_FILE = "beginImportFile";
//    private static final String READ_IMPORT_FILE = "readImportFile";
//    private static final String RESET = "reset";
//
//    ///////////////////////////////////////////////////////////////////
//
//    private enum State {
//        IDLE,
//        IMPORT_REQUESTED,
//        IMPORTING,
//        IMPORT_CANCELED,
//    }
//
//    private static final class ImportedFile {
//        public final String file;
//        public final int size;
//        public final ByteArrayInputStream data;
//
//        private ImportedFile(final String file, final byte[] data) {
//            this.file = file;
//            this.size = data.length;
//            this.data = new ByteArrayInputStream(data);
//        }
//    }
//
//    public record ImportedFileInfo(String url, int size) { }
//
//    private static final class ImportFileRequest {
//        public final WeakReference<WebCardItemDevice> Device;
//
//        private ImportFileRequest(final WebCardItemDevice device) {
//            Device = new WeakReference<>(device);
//        }
//    }
//
//    ///////////////////////////////////////////////////////////////////
//
//    private static final Int2ObjectArrayMap<ImportFileRequest> importingDevices = new Int2ObjectArrayMap<>();
//    private static int nextImportId = 1;
//
//    private final TerminalUserProvider userProvider;
//    private State state;
//    private int importingId;
//    private ImportedFile importedFile;
//
//    ///////////////////////////////////////////////////////////////////
//
//    public WebCardItemDevice(final ItemStack identity, final TerminalUserProvider userProvider) {
//        super(identity, "web_card");
//        this.userProvider = userProvider;
//    }
//
//    ///////////////////////////////////////////////////////////////////
//
//    @Override
//    public void unmount() {
//        reset();
//    }
//
//    @Callback(name = REQUEST_IMPORT_FILE)
//    public boolean requestImportFile(@Parameter("url") String external_url, @Parameter("file")  String file_name) {
//        if (state != State.IDLE) {
//            throw new IllegalStateException("invalid state");
//        }
//
//        final ArrayList<ServerPlayer> players = new ArrayList<>();
//        for (final Player player : userProvider.getTerminalUsers()) {
//            if (player instanceof final ServerPlayer serverPlayer) {
//                players.add(serverPlayer);
//            }
//        }
//
//        if (players.isEmpty()) {
//            return false;
//        }
//
//        state = State.IMPORT_REQUESTED;
//        importingId = nextImportId++;
//        synchronized (importingDevices) {
//            importingDevices.put(importingId, new ImportFileRequest(this));
//        }
//        try {
//            InputStream in = new URL(external_url).openStream();
//            Files.copy(in, Paths.get(file_name), StandardCopyOption.REPLACE_EXISTING);
//            System.out.println("Received from command request of external url :  " + external_url);
//            System.out.println("Received from command request for file name :  " + file_name);
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//
//        return true;
//    }
//
//    @Nullable
//    @Callback(name = BEGIN_IMPORT_FILE)
//    public ImportedFileInfo beginImportFile() {
//        if (state == State.IMPORT_CANCELED) {
//            reset();
//            throw new IllegalStateException("import was canceled");
//        }
//
//        if (state != State.IMPORT_REQUESTED) {
//            throw new IllegalStateException("invalid state");
//        }
//
//        if (importedFile == null) {
//            System.out.println("I am state null from beginImportFile : " + importedFile);
////            return null;
//        }
//
////        System.out.println("Started downloading from url name :  " + importedFile.url);
//        System.out.println("Started downloading from url file name :  " + importedFile.file);
//        state = State.IMPORTING;
//        return new ImportedFileInfo(importedFile.file, importedFile.size);
//    }
//
//    @Nullable
//    @Callback(name = READ_IMPORT_FILE)
//    public byte[] readImportFile() throws IOException {
//        if (state == State.IMPORT_CANCELED) {
//            reset();
//            throw new IllegalStateException("import was canceled");
//        }
//
//        if (state != State.IMPORTING) {
//            throw new IllegalStateException("invalid state");
//        }
//
//        if (importedFile == null) {
//            System.out.println("I am state null from readImportFile : " + importedFile);
//            return new byte[0];
//        }
//
//        final byte[] buffer = new byte[512];
//        final int count = importedFile.data.read(buffer);
//        if (count <= 0) {
//            reset();
//            return null;
//        }
//        if (count < buffer.length) {
//            final byte[] data = new byte[count];
//            System.arraycopy(buffer, 0, data, 0, count);
//            return data;
//        } else {
//            return buffer;
//        }
//    }
//
//    @Callback(name = RESET)
//    public void reset() {
//        state = State.IDLE;
//        importedFile = null;
//        synchronized (importingDevices) {
//            importingDevices.remove(importingId);
//        }
//    }
//
//    @Override
//    public void getDeviceDocumentation(final DeviceVisitor visitor) {
//        visitor.visitCallback(BEGIN_IMPORT_FILE)
//            .description("Begins a file import operation. This will prompt present users to select " +
//                "an externally stored file for import. If multiple users are present, the " +
//                "first user to select a file will have their file uploaded. Use the " +
//                READ_IMPORT_FILE + "() method to read the contents of the file being imported.\n" +
//                "This method may error if the device is currently exporting or importing.");
//        visitor.visitCallback(READ_IMPORT_FILE)
//            .description("Tries to read some data from a file being imported. Returns zero length " +
//                "data if no data is available yet. Returns null when no more data is " +
//                "available.\n" +
//                "This method may error if the device is not currently importing or the " +
//                "import was interrupted.")
//            .returnValueDescription("data from the file being imported.");
//        visitor.visitCallback(RESET)
//            .description("Resets the device and cancels any currently running export or import operation.");
//    }
//}
