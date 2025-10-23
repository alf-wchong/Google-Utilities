import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

// --- CONFIGURATION ---

// IMPORTANT: Replace these constants with your actual values
final String APPLICATION_NAME = "GroovyDriveCleaner"
final String SERVICE_ACCOUNT_KEY_PATH = "path/to/your/service_account_key.json" // e.g., "drive-service-key.json"
final String TARGET_FOLDER_ID = "YOUR_TARGET_FOLDER_ID" // The ID of the folder to empty
final String LOCAL_OUTPUT_DIR = "downloaded_files" // Local directory for saving files

// Define the scopes needed. Drive is required for full access.
final List<String> SCOPES = Arrays.asList(DriveScopes.DRIVE)

// A map to handle conversion of Google Workspace file types to standard formats
final Map<String, String> GOOGLE_MIME_TYPES = [
    'application/vnd.google-apps.document'      : 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', // DOCX
    'application/vnd.google-apps.spreadsheet'   : 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', // XLSX
    'application/vnd.google-apps.presentation'  : 'application/vnd.openxmlformats-officedocument.presentationml.presentation', // PPTX
    'application/vnd.google-apps.drawing'       : 'application/pdf', // PDF
    'application/vnd.google-apps.script'        : 'application/vnd.google-apps.script+json', // JSON
]

final Map<String, String> GOOGLE_FILE_EXTENSIONS = [
    'application/vnd.google-apps.document'      : 'docx',
    'application/vnd.google-apps.spreadsheet'   : 'xlsx',
    'application/vnd.google-apps.presentation'  : 'pptx',
    'application/vnd.google-apps.drawing'       : 'pdf',
    'application/vnd.google-apps.script'        : 'json',
]

// --- UTILITY FUNCTIONS ---

/**
 * Initializes and returns the authenticated Google Drive Service instance.
 */
def getDriveService() {
    println "Attempting to authorize using Service Account key..."
    
    // Load credentials from the JSON key file
    def credentialsStream = new FileInputStream(SERVICE_ACCOUNT_KEY_PATH)
    GoogleCredentials credentials = GoogleCredentials.fromStream(credentialsStream).createScoped(SCOPES)
    
    // Build the HTTP transport and the Drive service
    def httpTransport = GoogleNetHttpTransport.newTrustedTransport()
    
    return new Drive.Builder(
        httpTransport,
        GsonFactory.getDefaultInstance(),
        new HttpCredentialsAdapter(credentials) // Adapts GoogleCredentials for the API client
    )
    .setApplicationName(APPLICATION_NAME)
    .build()
}

/**
 * Handles the download of a single file, supporting both media files and Google Workspace documents.
 */
def downloadFile(Drive driveService, File driveFile, Path outputDir) {
    String fileId = driveFile.getId()
    String mimeType = driveFile.getMimeType()
    String fileName = driveFile.getName()
    
    // Check if the file is a Google Workspace document that needs exporting
    if (GOOGLE_MIME_TYPES.containsKey(mimeType)) {
        String exportMimeType = GOOGLE_MIME_TYPES.get(mimeType)
        String extension = GOOGLE_FILE_EXTENSIONS.get(mimeType)
        
        println "Exporting Google Workspace file: ${fileName} to .${extension} (MIME: ${exportMimeType})"
        
        // Use files().export() for Google Workspace files
        def exportRequest = driveService.files().export(fileId, exportMimeType)
        
        // Add the correct file extension to the name
        def localPath = outputDir.resolve("${fileName}.${extension}")
        
        // Execute the request and save the content
        exportRequest.executeMediaAndDownloadTo(new FileOutputStream(localPath.toFile()))
        
        println "SUCCESS: Exported to ${localPath}"
        return true
    } else {
        // Standard media file download
        println "Downloading media file: ${fileName} (MIME: ${mimeType})"
        
        // Use files().get() with alt=media for regular files
        def downloadRequest = driveService.files().get(fileId)
        
        // Determine the local filename (Drive usually provides an extension for media files)
        def localPath = outputDir.resolve(fileName)
        
        // Execute the request and save the content
        downloadRequest.executeMediaAndDownloadTo(new FileOutputStream(localPath.toFile()))
        
        println "SUCCESS: Downloaded to ${localPath}"
        return true
    }
}

/**
 * Moves a file in Google Drive to the trash.
 */
def trashFile(Drive driveService, String fileId, String fileName) {
    try {
        // Create a minimal File object and set the trashed property to true
        File emptyFile = new File().setTrashed(true)
        
        // Use files().update() to apply the trash status
        driveService.files().update(fileId, emptyFile).execute()
        println "TRASHED: Successfully moved file to trash: ${fileName}"
        return true
    } catch (Exception e) {
        println "ERROR TRASHING: Could not move file '${fileName}' to trash: ${e.getMessage()}"
        return false
    }
}

// --- MAIN EXECUTION LOGIC ---

try {
    // 1. Setup Service
    Drive driveService = getDriveService()

    // 2. Prepare Local Directory
    Path outputDir = Paths.get(LOCAL_OUTPUT_DIR)
    Files.createDirectories(outputDir)
    println "Local output directory ready: ${outputDir.toAbsolutePath()}"

    // 3. Query Files
    println "Searching for files in folder ID: ${TARGET_FOLDER_ID}"
    
    String query = "'${TARGET_FOLDER_ID}' in parents and trashed = false"
    def fileList = driveService.files().list()
        .setQ(query)
        .setPageSize(100) // Fetch up to 100 results per page
        .setFields("nextPageToken, files(id, name, mimeType)")
        .execute()

    def filesToProcess = fileList.getFiles()

    if (filesToProcess.isEmpty()) {
        println "No files found in the specified folder."
        return
    }

    println "Found ${filesToProcess.size()} files. Starting export and cleanup..."

    // 4. Process Each File
    filesToProcess.each { driveFile ->
        println "--- Processing: ${driveFile.getName()} ---"
        
        boolean downloadSuccess = false
        try {
            downloadSuccess = downloadFile(driveService, driveFile, outputDir)
        } catch (Exception e) {
            println "CRITICAL DOWNLOAD ERROR for ${driveFile.getName()}: ${e.getMessage()}"
        }

        // 5. Trash the file only if the download was successful
        if (downloadSuccess) {
            trashFile(driveService, driveFile.getId(), driveFile.getName())
        } else {
            println "SKIP TRASH: Download failed for ${driveFile.getName()}. Skipping trash operation."
        }
        println "--------------------------------"
    }

    println "Script execution complete."

} catch (FileNotFoundException e) {
    println "ERROR: Service account key file not found at: ${SERVICE_ACCOUNT_KEY_PATH}"
    println "Please check the path and ensure the file exists."
    
} catch (Exception e) {
    println "An unexpected error occurred during API execution: ${e.getMessage()}"
    e.printStackTrace()
}

