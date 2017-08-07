package com.android.cplusplus;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;

import com.android.cplusplus.JavaReader.JavaFile;

/**
 * 
 * @author yangbin.li
 *
 */
public class Core {
    
    static final boolean DEBUG_MODE = true;
    
    static final ArrayList<String> sTranslatedFiles = new ArrayList<>();
    static {
        sTranslatedFiles.add("BatchedScanResult.java");
        sTranslatedFiles.add("BatchedScanSettings.java");
        sTranslatedFiles.add("IWifiManager.aidl");
        sTranslatedFiles.add("ScanResult.java");
        sTranslatedFiles.add("ScanSettings.java");
        sTranslatedFiles.add("SupplicantState.java");
        sTranslatedFiles.add("WifiActivityEnergyInfo.java");
        sTranslatedFiles.add("WifiConnectionStatistics.java");
        sTranslatedFiles.add("WifiEnterpriseConfig.java");
        sTranslatedFiles.add("WifiLinkLayerStats.java");
        sTranslatedFiles.add("WifiNetworkConnectionStatistics.java");
        sTranslatedFiles.add("WifiSsid.java");
        sTranslatedFiles.add("WifiConfiguration.java");
        sTranslatedFiles.add("WifiInfo.java");
        sTranslatedFiles.add("WifiChannel.java");
        sTranslatedFiles.add("WifiManager.java");
        sTranslatedFiles.add("WpsInfo.java");
        sTranslatedFiles.add("WpsResult.java");
    }

    public static void main(String[] args) {
        
//        String srcPath = "/work/cetc/android 6.0.1/TQi.MX6Q V3.6_Android资源.part1/opt/EmbedSky/TQIMX6/android-6.0.1-2.1.0/libcore/luni/src/main/java/java/util/BitSet.java";
//        
//        String dstPath = "/work/wifi2c++/cetc_os/tool/wifiapp";
        
        // base directory
//        String srcPath = "/work/cetc/android 6.0.1/TQi.MX6Q V3.6_Android资源.part1/opt/"
//              + "EmbedSky/TQIMX6/android-6.0.1-2.1.0/frameworks/base/wifi/java/android/net/wifi";
//        
//        String dstPath = "/work/wifi2c++/cetc_os/frameworks/base/wifi/java/android/net/wifi";
        
        // opt directory
        String srcPath = "/work/cetc/android 6.0.1/TQi.MX6Q V3.6_Android资源.part1/opt/"
              + "EmbedSky/TQIMX6/android-6.0.1-2.1.0/frameworks/opt/net/wifi/"
              + "service/java/com/android/server/wifi";
        
        String dstPath = "/work/wifi2c++/cetc_os/frameworks/opt/net/wifi/service/"
              + "java/com/android/server/wifi";
        
        // service directory
//        String srcPath = "/work/wifi2c++/cetc_os/tool/wifiservice/java/com/android/server/wifi";
//          
//        String dstPath = "/work/wifi2c++/cetc_os/tool/wifiservice_cpp/java/com/android/server/wifi";
        
        String missingPath = dstPath + "/MissingHeader.txt";

        if (DEBUG_MODE) {
            JavaFile file = sReader.
//                    read("/home/archermind/workspace/java2c++/src/com/android/cplusplus/IAccessibilityServiceConnection.aidl");
//                    read(srcPath);
//          read("/home/archermind/workspace/frameworks/base/core/java/android/app/Activity.java");
//          read("/home/archermind/workspace/frameworks/base/core/java/android/view/ContextThemeWrapper.java");
            read("/home/archermind/workspace/java2c++/src/com/android/cplusplus/Test.java");
//          read("/home/archermind/workspace/java2c++/src/com/android/cplusplus/Core.java");
//          read("/home/archermind/workspace/frameworks/base/core/java/android/app/job/JobInfo.aidl");
//          read("/home/archermind/workspace/frameworks/base/core/java/android/app/IActivityContainer.aidl");
  
            sWriter.write(file);
        } else {
            long startMillis = System.currentTimeMillis();
            System.out.println("Translation start");
            int[] out = new int[3];
            processDirectory(srcPath, dstPath, out);
            
            printMissingHeadersIfNecessary(missingPath);
            
            System.out.println("Translation done, total cost:" +
                    (System.currentTimeMillis() - startMillis) + "ms. " + out[0] + " files processed, "
                    + out[1] + " files generated, " + out[2] + " errors occurs.");
        }
    }
    
    static final JavaReader sReader = new JavaReader();
    static final CppWriter sWriter = new CppWriter();
    
    private static int[] processDirectory(String srcPath, String dstPath, int[] out) {
        File target = new File(srcPath);
        
        if (!target.exists()) {
            throw new RuntimeException("Path doesn't exist:" + srcPath);
        }
        
        if (out == null) {
            out = new int[3];
        }
        File[] childrenFile = target.isDirectory() ? target.listFiles() : new File[]{target};
        for (File file : childrenFile) {
            if (file.isDirectory()) {
                File dstDirectory = new File(dstPath + "/" + file.getName());
                boolean success = true;
                if (!dstDirectory.exists()) {
                    success = dstDirectory.mkdirs();
                }
                if (success) {
                    processDirectory(file.getAbsolutePath(), dstDirectory.getAbsolutePath(), out);
                }
            } else {
                if (sTranslatedFiles.contains(file.getName())) {
                    System.out.println("skip file:" + file.getName());
                    continue;
                }
                long millis = System.currentTimeMillis();
                System.out.println("...processing file:" + file.getName());
                out[0]++;
                
                try {
                    out[1] += sWriter.write(sReader.read(file), dstPath);
                } catch (Exception e) {
                    out[2]++;
                    System.err.println("err:" + file.getAbsolutePath());
                    e.printStackTrace();
                }
                JavaCodeReader.printAndClearCodeParserRecord();
                
                long cost = System.currentTimeMillis() - millis;
                System.out.println("done. processing cost:" + cost + "ms");
                System.out.println();
                
                try {
                    Thread.sleep(cost < 50 ? 50 - cost : 10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        return out;
    }
    
    private static void printMissingHeadersIfNecessary(String path) {
        File outFile = new File(path);
        if (outFile.exists()) {
            outFile.delete();
        }
        try {
            if (!outFile.createNewFile()) {
                throw new RuntimeException("Can not create file:" + outFile.getAbsolutePath());
            }
        } catch (IOException e) {
        }
        PrintStream out = null;
        try {
            out = new PrintStream(new FileOutputStream(outFile), true);
            boolean noMissing = true;
            int index = 0;
            for (Entry<String, ExistenceRecord> entry : sHeaderExistence.entrySet()) {
                String headerFullName = entry.getKey();
                ExistenceRecord record = entry.getValue();
                if (!record.existed) {
                    noMissing = false;
                    
                    out.println(++index + ". Missing file:" + record.briefName + " fullname:" + headerFullName);
                    int size = record.touchedFiles.size();
                    out.println("Required by " + size + " files.");
                    for (int i = 0; i < size; i++) {
                        out.println("    " + (i + 1) + ". file:" + record.touchedFiles.get(i));
                    }
                    out.println();
                }
            }
            if (noMissing) {
                out.println("No missing!!");
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                out.close();
                out = null;
            }
        }
    }
    
    static final HashMap<String, ExistenceRecord> sHeaderExistence = new HashMap<>();
    
    static class ExistenceRecord {
        String briefName;
        boolean existed;
        ArrayList<String> touchedFiles = new ArrayList<>();
        
        ExistenceRecord(String breifName, boolean existed, String where) {
            this.briefName = breifName;
            this.existed = existed;
            this.touchedFiles.add(where);
        }

        void touchFrom(String where) {
            for (int i = 0; i < touchedFiles.size(); i++) {
                if (touchedFiles.get(i).equals(where)) {
                    return;
                }
            }
            touchedFiles.add(where);
        }
    }
    
//    static final ArrayList<String> sSpecialHeaderFileList = new ArrayList<>();
//    static {
//        sSpecialHeaderFileList.add("util.AsyncChannel");
//        sSpecialHeaderFileList.add("util.Protocol");
//    }
//    
//    static boolean isPreservedHeaderFile(String headerName) {
//        for (int i = 0; i < sSpecialHeaderFileList.size(); i++) {
//            if (headerName.endsWith(sSpecialHeaderFileList.get(i))) {
//                return true;
//            }
//        }
//        return false;
//    }
    
    static boolean isHeaderFileExisted(String from, String name, String fullName) {
        if (sHeaderExistence.containsKey(fullName)) {
            ExistenceRecord record = sHeaderExistence.get(fullName);
            record.touchFrom(from);
            return record.existed;
        }
        final String directoryPath = "/work/wifi2c++/cetc_os/to-cm/ReMo_V2";
        File directory = new File(directoryPath);
        if (!directory.exists() || !directory.isDirectory()) {
            throw new RuntimeException("Invalid header path. directoryPath:" + directoryPath);
        }
        boolean existed = isHeaderFileExistedRecursively(directory, name);
        sHeaderExistence.put(fullName, new ExistenceRecord(name, existed, from));
        return existed;
    }
    
    private static boolean isHeaderFileExistedRecursively(File directory, String name) {
        File[] children = directory.listFiles();
        ArrayList<File> directories = new ArrayList<>();
        for (int i = 0; i < children.length; i++) {
            File file = children[i];
            if (file.isDirectory()) {
                directories.add(file);
            } else if (file.getName().equals(name)) {
                return true;
            }
        }
        for (int i = 0; i < directories.size(); i++) {
            if (isHeaderFileExistedRecursively(directories.get(i), name)) {
                return true;
            }
        }
        return false;
    }
}
