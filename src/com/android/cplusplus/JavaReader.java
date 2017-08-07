package com.android.cplusplus;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

interface CodeLineReader {
    String nextLine(boolean trim, boolean collectCommentLine);
    String nextLine();
    ArrayList<String> collectPendingComment();
    void clearComment();
    int getCurrentLineIndex();
}

public final class JavaReader implements CodeLineReader {
    
    private static final boolean PROGRESSING_DEBUG = true;
    
    private static final Pattern sBrance_L = Pattern.compile("[{]");
    private static final Pattern sBrance_R = Pattern.compile("[}]");
    private static final Pattern sSemicolon = Pattern.compile("[;]");
//    private static final Pattern sComma = Pattern.compile("[,]");
    
    private static final Pattern sDashPatternL = Pattern.compile("[/][*]");
    private static final Pattern sDashPatternR = Pattern.compile("[*][/]");
    private static final Pattern sDashPatternC = Pattern.compile("[/][/]");
    private static final Pattern sAnnoPattern =
            Pattern.compile("@\\w+\\.\\w+\\s*|@\\w+\\s*");
    
    private boolean mCommentNotCompleted;
    private ArrayList<String> mPendingComment = new ArrayList<>();
    private int mAnnotationAnchor;
    
    private int mLine;
    private boolean mIsAIDLMode;
    private long mLastProgressingPrintMillis;
    private boolean mDidProgressingDebug;
    private BufferedReader mReader;
    private JavaCodeReader mJavaCodeReader = new JavaCodeReader();
    
    static class LineParser {
        
        static Pattern sQuoPattern = Pattern.compile("\"|\'");
        
        static LineParser sPool;
        static int sPoolSize;
        
        String line;
        Matcher branceLM;
        Matcher branceRM;
        Matcher semicolonM;
//        Matcher commaM;
        Matcher atM;
        
        LineParser next;
        int[] quotationArray;

        static LineParser obtain(String line) {
            if (sPool == null) {
                return new LineParser(line);
            }
            LineParser out = sPool;
            out.line = line;
            out.initQuotation();
            sPool = out.next;
            sPoolSize--;
            return out;
        }
        
        private LineParser(String line) {
            this.line = line;
            initQuotation();
        }
        
        private void initQuotation() {
            Matcher quoMm = sQuoPattern.matcher(line);
            int index = 0;
            while (quoMm.find()) {
                if (quotationArray == null || index >= quotationArray.length) {
                    int[] src = quotationArray;
                    int capacity = src == null ? 2 : (src.length + 2);
                    quotationArray = new int[capacity];
                    if (src != null) {
                        System.arraycopy(src, 0, quotationArray, 0, src.length);
                    }
                }
                quotationArray[index] = quoMm.start();
                index++;
            }
            if (quotationArray != null) {
                for (int i = index; i < quotationArray.length; i++) {
                    quotationArray[i] = -1;
                }
            }
        }

        void recycle() {
            branceLM = branceRM = /*commaM =*/ semicolonM = atM = null;
            line = null;
            quotationArray = null;
            
            if (sPoolSize < 5) {
                next = sPool;
                sPool = this;
                sPoolSize++;
            }
        }
        
//        int nextComma() {
//            if (commaM == null) {
//                commaM = sComma.matcher(line);
//            }
//            return findNext(commaM);
//        }
        
        int nextSemicolon() {
            if (semicolonM == null) {
                semicolonM = sSemicolon.matcher(line);
            }
            return findNext(semicolonM);
        }
        
        int nextLeftBrance() {
            if (branceLM == null) {
                branceLM = sBrance_L.matcher(line);
            }
            return findNext(branceLM);
        }
        
        int nextRightBrance() {
            if (branceRM == null) {
                branceRM = sBrance_R.matcher(line);
            }
            return findNext(branceRM);
        }
        
        int nextAnnotation() {
            if (atM == null) {
                atM = sAnnoPattern.matcher(line);
            }
            return findNext(atM);
        }
        
        int findNext(Matcher matcher) {
            outter:while (matcher.find()) {
                int index = matcher.start();
                if (quotationArray != null) {
                    for (int i = 0; i < quotationArray.length - 1; i++) {
                        if (quotationArray[i] != -1 && quotationArray[i] < index &&
                                quotationArray[i + 1] > index) {
                            /*
                             *  ""...""...X...""
                             *  the count of '"' before X must be an even number
                             */
                            if (i % 2 == 0) {
                                continue outter;
                            }
                        }
                    }
                }
                return index;
            }
            return -1;
        }
    }
    
    JavaReader() {
    }
    
    @Override
    public String nextLine() {
        return nextLine(true, false);
    }
    
    @Override
    public String nextLine(boolean trim, boolean collectCommentLine) {
        String tempString = null;
        try {
            while ((tempString = mReader.readLine()) != null) {
                mLine++;
                
                if (PROGRESSING_DEBUG) {
                    long currentMillis = System.currentTimeMillis();
                    if (currentMillis - mLastProgressingPrintMillis > 250) {
                        mLastProgressingPrintMillis = currentMillis;
                        mDidProgressingDebug |= true;
                        System.out.print(" *");
                    }
                }
                
                if (trim) {
                    tempString = tempString.trim();
                }
                if (tempString.length() == 0) {
                    continue;
                }
                String codeLine = null;
                codeLine = purgeComment(tempString);
                if (codeLine.length() == 0) {
                    if (collectCommentLine) {
                        mPendingComment.add(tempString);
                    }
                    continue;
                }
                if (!mCommentNotCompleted) {
                    codeLine = purgeAnnotation(codeLine);
                    if (codeLine.length() == 0) {
                        continue;
                    }
                }
                return codeLine;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "#_#";
    }
    
    @Override
    public ArrayList<String> collectPendingComment() {
        return mPendingComment.size() > 0 ? new ArrayList<>(mPendingComment) : null;
    }

    @Override
    public void clearComment() {
        mPendingComment.clear();
    }
    
    @Override
    public int getCurrentLineIndex() {
        return mLine;
    }
    
    public JavaFile read(File file) {
        String fileName = file.getName();
        
        if (!fileName.endsWith(".java") && !fileName.endsWith(".aidl")) {
            return null;
        }
        mLine = 0;
        mCommentNotCompleted = false;
        mPendingComment.clear();
        mAnnotationAnchor = 0;
        mIsAIDLMode = fileName.endsWith(".aidl");
        mDidProgressingDebug = false;
        mLastProgressingPrintMillis = System.currentTimeMillis() + 251;
        
        try {
            mReader = new BufferedReader(new FileReader(file));
        } catch (IOException e) {
            e.printStackTrace();
        }
        JavaFile javaFile = new JavaFile();
        javaFile.path = file.getAbsolutePath();
        javaFile.isAidl = mIsAIDLMode;
        
        String line = null;
        do {
            line = nextLine();
            
            if (line.startsWith("package")) {
                String packageName = line.substring(8, line.indexOf(";"));
                javaFile.packageName = packageName;
            } else if (line.startsWith("import")) {
                if (javaFile.importStatements == null) {
                    javaFile.isImportStatic = new ArrayList<>();
                    javaFile.importStatements = new ArrayList<>();
                }
                String afterImport = line.substring(7, line.indexOf(";"));
                if (afterImport.startsWith("static")) {
                    afterImport = line.substring(7);
                    javaFile.isImportStatic.add(true);
                } else {
                    javaFile.isImportStatic.add(false);
                }
                javaFile.importStatements.add(afterImport);
            } else if (mIsAIDLMode && line.startsWith("parcelable")) {
                if (javaFile.parcelableDeclaration == null) {
                    javaFile.parcelableDeclaration = new ArrayList<>();
                }
                String afterParcelable = line.substring(11, line.indexOf(";"));
                javaFile.parcelableDeclaration.add(afterParcelable);
            } else {
                break;
            }
        } while (true);
        
        Clazz clazz = null;
        boolean first = true;
        do {
            clazz = (Clazz) processNextStatement(first && line.length() > 0 ? new String[]{line} :
                null, null, this);
            first = false;
            if (clazz == null) {
                break;
            }
            clazz.packageName = javaFile.packageName;
            if (clazz.accessLevel == JavaStatement.LEVEL_PUBLIC) {
                javaFile.primeClass = clazz;
            } else {
                if (javaFile.otherClass == null) {
                    javaFile.otherClass = new ArrayList<>();
                }
                javaFile.otherClass.add(clazz);
            }
        } while (true);
        
        if (javaFile.primeClass == null && javaFile.otherClass != null) {
            javaFile.primeClass = javaFile.otherClass.get(0);
            javaFile.otherClass.remove(0);
        }
        
        if (mReader != null) {
            try {
                mReader.close();
                mReader = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        if (mDidProgressingDebug) {
            System.out.println();
        }
        
        return javaFile;
    }
    
    public JavaFile read(String filePath) {
        if (filePath == null) {
            return null;
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }
        
        return read(file);
    }
    
    void installAnonymousClass(JavaStatement current, String extendedClass,
            String anonyName, CodeLineReader reader, ArrayList<String> finalLocalArgs) {
        JavaStatement parent = current.parentStatement;
        if (parent == null || parent.type != JavaStatement.TYPE_CLASS) {
            throw new RuntimeException("Logic crash");
        }
        
        Clazz outterClass = (Clazz) parent;
        Clazz clazz = new Clazz();
        clazz.hierarchyIndex = current.hierarchyIndex;
        clazz.line = current.line;
        clazz.parentStatement = parent;
        clazz.accessLevel = JavaStatement.LEVEL_PRIVATE;
        clazz.name = anonyName != null ? anonyName : "Inner_anonymous_";
        clazz.isInnerClass = true;
        clazz.isStatic = current.isStatic;
        clazz.extendedClazz = extendedClass;
        clazz.isAutoGenerated = true;
        
        if (finalLocalArgs != null && finalLocalArgs.size() > 0) {
            ClassParagraph paragraph = (ClassParagraph) clazz.paragraph;
            paragraph.constructionMethods = new ArrayList<>();
            paragraph.fields = new ArrayList<>();
            
            JavaMethod consMethod = new JavaMethod();
            consMethod.parentStatement = clazz;
            consMethod.line = Integer.MAX_VALUE;
            consMethod.isAutoGenerated = true;
            consMethod.accessLevel = JavaField.LEVEL_PUBLIC;
            consMethod.isConstruction = true;
            consMethod.name = clazz.name;
            consMethod.paragraph = new JavaCodeParagraph();
            consMethod.parameters = new ArrayList<>();
            for (int i = 0; i < finalLocalArgs.size(); i += 2) {
                String type = finalLocalArgs.get(i);
                String name = finalLocalArgs.get(i + 1);
                
                consMethod.parameters.add(type + " " + name);
                
                JavaField outterField = new JavaField();
                outterField.line = Integer.MAX_VALUE;
                outterField.parentStatement = clazz;
                outterField.isAutoGenerated = true;
                outterField.isFinal = true;
                outterField.feildType = type;
                outterField.name = name + "_args";
                paragraph.fields.add(outterField);
            }
            paragraph.constructionMethods.add(consMethod);
            
        }
        
        installParagraph(null, clazz, reader);
        outterClass.paragraph.addStatement(clazz);
    }
    
    /**
     * Figure out minimum digit but greater than zero
     */
//    private static int minimumIndex(int a, int b, int c, int d) {
//        return minimumIndex(minimumIndex(a, b), minimumIndex(c, d));
//    }
    static int minimumIndex(int a, int b, int c) {
        return minimumIndex(minimumIndex(a, b), c);
    }
    static int minimumIndex(int a, int b) {
        if (a >= 0 && b >= 0) {
            return Math.min(a, b);
        } else if (a >= 0 || b >= 0) {
            return Math.max(a, b);
        } else {
            return -1;
        }
    }
    
    private JavaStatement processNextStatement(String[] surplus, JavaStatement current,
            CodeLineReader reader) {
        StringBuffer cachedStr = null;
        int leftBranceIndex;
        int rightBranceIndex;
        int semicolonIndex;
//        int commaIndex;
        int targetIndex;
        String currentLine;
        boolean firstIterate = true;
        do {
            currentLine = firstIterate && surplus != null ? surplus[0] : reader.nextLine(true, true);
            if ("#_#".equals(currentLine)) {
                return null;
            }
            firstIterate = false;
            if (currentLine == null || currentLine.length() == 0) {
                continue;
            }
            
            LineParser lineParser = LineParser.obtain(currentLine);
            
            semicolonIndex = lineParser.nextSemicolon();
            leftBranceIndex = lineParser.nextLeftBrance();
            rightBranceIndex = lineParser.nextRightBrance();
//            commaIndex = current != null && current.type == Statement.TYPE_ENUM_CLASS ? 
//                    lineParser.nextComma() : -1;
            targetIndex = -1;
            
            lineParser.recycle();
            
            if (semicolonIndex == 0 || leftBranceIndex == 0  || rightBranceIndex == 0) {
                targetIndex = 0;
                if (semicolonIndex == 0 && (cachedStr == null || cachedStr.length() == 0)) {
                    continue;
                }
                break;
            }
            
            targetIndex = minimumIndex(leftBranceIndex, rightBranceIndex, semicolonIndex);
            
            if (cachedStr == null) {
                cachedStr = new StringBuffer();
            }
            
            if (targetIndex > 0) {
                cachedStr.append(currentLine.substring(0, targetIndex));
                break;
            } else {
                cachedStr.append(currentLine + " ");
            }
        } while (true);
        
        if (targetIndex == rightBranceIndex) {
            reader.clearComment();
        } else {
            JavaStatement statement = createStatement(cachedStr != null ? cachedStr.toString() : "",
                    current, reader.getCurrentLineIndex());
            
            if (statement == null) {
                return null;
            }
            
            statement.parentStatement = current;
            statement.relatedComment = reader.collectPendingComment();
            reader.clearComment();
            
            statement.setHierarchyIndex(current != null ? current.hierarchyIndex + 1 : 1);
            boolean doNextStatementProcessing = targetIndex == semicolonIndex;// target == ';'
            
            if (doNextStatementProcessing) {
                if (surplus != null && targetIndex < currentLine.length() - 1) {
                    surplus[0] = currentLine.substring(targetIndex + 1);
                }
            } else {
                // target == leftBrance '{'
                String temp = currentLine.substring(leftBranceIndex + 1);
                installParagraph(temp.length() > 0 ? temp : null, statement, reader);
            }
            return statement;
        }
        return null;
    }
    
    private void installParagraph(String surplus, JavaStatement current, CodeLineReader reader) {
        JavaParagraph paragraph = current.paragraph;
        if (paragraph == null) {
            switch (current.type) {
            case JavaStatement.TYPE_CLASS:
//            case Statement.TYPE_ENUM_CLASS:
                paragraph = new ClassParagraph();
                paragraph.type = JavaParagraph.TYPE_CLASS;
                break;
            case JavaStatement.TYPE_CODE_BLOCK:
                paragraph = new JavaCodeParagraph();
                paragraph.type = current.isStatic ? JavaParagraph.TYPE_STATIC :
                    JavaParagraph.TYPE_CONSTRUCTION;
                break;
            case JavaStatement.TYPE_FIELD:
                if (((JavaField) current).isArray) {
                    paragraph = new JavaCodeParagraph();
                    paragraph.type = JavaParagraph.TYPE_ARRAY;
                } else {
                    paragraph = new ClassParagraph();
                    paragraph.type = JavaParagraph.TYPE_ANONYMOUS_CLASS;
                }
                break;
            case JavaStatement.TYPE_METHOD:
                paragraph = new JavaCodeParagraph();
                paragraph.type = JavaParagraph.TYPE_METHOD;
                break;
            case JavaStatement.TYPE_ENUM:
                paragraph = new JavaCodeParagraph();
                paragraph.type = JavaParagraph.TYPE_ARRAY;
                break;
            case JavaStatement.TYPE_ANNO:
                paragraph = new JavaCodeParagraph();
                paragraph.type = JavaParagraph.TYPE_CODE;
                break;
            }
            current.paragraph = paragraph;
        }
        paragraph.owner = current;
        paragraph.setHierarchyIndex(current.hierarchyIndex + 1);
        paragraph.line = reader.getCurrentLineIndex();
        
        if (paragraph.type == JavaParagraph.TYPE_CLASS || 
                paragraph.type == JavaParagraph.TYPE_ANONYMOUS_CLASS) {
            JavaStatement statement = null;
            String[] surplusContainer = new String[1];
            surplusContainer[0] = surplus;
            do {
                statement = processNextStatement(surplusContainer, current, reader);
                if (statement == null) {
                    break;
                }
                paragraph.addStatement(statement);
            } while (true);
        } else {
            String currentLine;
            int branceAnchor = 1;
            boolean doCodeProcessing = current.type == JavaStatement.TYPE_METHOD || 
                    current.type == JavaStatement.TYPE_CODE_BLOCK;
            boolean doTrim = doCodeProcessing;
            
            boolean firstTime = true;
            Pattern firstCharPa = Pattern.compile("\\w");
            int tabEndIndex = 0;
            boolean codeProcessingAborted = false;
            if (doCodeProcessing) {
                mJavaCodeReader.start(paragraph);
            }
            do {
                currentLine = surplus != null ? surplus : reader.nextLine(doTrim, false);
                if (!doTrim && surplus == null && firstTime) {
                    Matcher firstCharM = firstCharPa.matcher(currentLine);
                    if (firstCharM.find()) {
                        tabEndIndex = firstCharM.start();
                        firstTime = false;
                    }
                }
                surplus = null;
                int leftBIndex = -1;
                int rightBIndex = -1;
                
                if (currentLine.length() > 0) {
                    LineParser parser = LineParser.obtain(currentLine);
                    boolean findNexLeftBIndex = true;
                    boolean findNextRightBIndex = true;
                    
                    do {
                        leftBIndex = findNexLeftBIndex ? parser.nextLeftBrance() : leftBIndex;
                        rightBIndex = findNextRightBIndex ? parser.nextRightBrance() : rightBIndex;
                        
                        if (leftBIndex < 0 && rightBIndex < 0) {
                            break;
                        }
                        
                        int minimumIndex = minimumIndex(leftBIndex, rightBIndex);
                        if (minimumIndex == leftBIndex) {
                            branceAnchor++;
                            findNexLeftBIndex = true;
                            findNextRightBIndex = false;
                        } else {
                            branceAnchor--;
                            findNextRightBIndex = true;
                            findNexLeftBIndex = false;
                        }
                    } while (branceAnchor > 0);
                    
                    parser.recycle();
                }
                
                if (branceAnchor < 0) {
                    throw new IllegalStateException("Do check if your java grammar is correct");
                }
                
                if (branceAnchor == 0 && rightBIndex >= 0) {
                    currentLine = currentLine.substring(0, rightBIndex).trim();
                } else {
                    if (tabEndIndex > 0) {
                        if (currentLine.length() > tabEndIndex && currentLine.charAt(tabEndIndex - 1) == ' ') {
                            currentLine = currentLine.substring(tabEndIndex);
                        }
                    }
                }
                if (currentLine.length() > 0) {
                    if (doCodeProcessing && !codeProcessingAborted) {
                        if (Core.DEBUG_MODE) {
                            mJavaCodeReader.processCodeLine(currentLine);
                        } else {
                            try {
                                mJavaCodeReader.processCodeLine(currentLine);
                            } catch (Exception e) {
                                e.printStackTrace();
                                codeProcessingAborted = true;
                            }
                        }
                    }
                    JavaCodeParagraph codeParagraph = (JavaCodeParagraph) paragraph;
                    codeParagraph.addCode(reader.getCurrentLineIndex(), currentLine);
                }
            } while (branceAnchor > 0);
            
            if (doCodeProcessing) {
                JavaCodeReader.CodeParagraph parsedParagraph = null;
                if (Core.DEBUG_MODE) {
                    parsedParagraph = mJavaCodeReader.finish();
                } else {
                    try {
                        parsedParagraph = mJavaCodeReader.finish();
                    } catch (Exception e) {
                        e.printStackTrace();
                        codeProcessingAborted = true;
                    }
                }
                if (!codeProcessingAborted) {
                    JavaCodeParagraph codeParagraph = (JavaCodeParagraph) paragraph;
                    codeParagraph.setParsedCode(parsedParagraph);
                }
            }
        }
    }
    
    private boolean compareKeyWord(String keyWord, String word/*, boolean[] consumed*/) {
        if (keyWord.equals(word)) {
//            consumed[0] = true;
            return true;
        } else {
//            consumed[0] = false;
            return false;
        }
    }
    
    private ArrayList<String> processTemplates(String word, boolean[] consumed) {
        if (word != null && word.startsWith("<") && word.endsWith(">")) {
            ArrayList<String> templates = new ArrayList<>();
            word = word.substring(1, word.length() - 1);
            if (word.contains(",")) {
                String[] eachOne = word.split(",");
                for (int i = 0; i < eachOne.length; i++) {
                    templates.add(eachOne[i]);
                }
            } else {
                templates.add(word);
            }
            consumed[0] = true;
            return templates;
        } else {
            consumed[0] = false;
        }
        return null;
    }
    
    private static final String[] sKeywords = {
            /*0*/"public", 
            /*1*/"protected",
            /*2*/"private",
            /*3*/"volatile",
            /*4*/"static",
            /*5*/"final",
            /*6*/"abstract",
            /*7*/"class",
            /*8*/"interface",
            /*9*/"extends",
            /*10*/"implements",
            /*11*/"enum",
            /*12*/"@interface",
            /*13*/"throws",
            /*14*/"@Override",
            /*15*/"synchronized",
            /*16*/"native"};
    
    static int countOfSpecifiedChar(String target, char ch) {
        int sum = 0;
        for (int i = 0; i < target.length(); i++) {
            if (target.charAt(i) == ch) {
                sum++;
            }
        }
        return sum;
    }

    private JavaStatement createStatement(String code, JavaStatement currentStatement, int line) {
        boolean[] matchSlot = new boolean[sKeywords.length];
        int processFlag = 0;
        LinkedList<String> unknownStr = null;
        String[] wordArray = code.split("\\s+");
        ArrayList<String> tempStrCache = null;
        int leftAnchor = 0;
        for (String word : wordArray) {
            if (word.length() == 0) {
                continue;
            }
            leftAnchor += countOfSpecifiedChar(word, '<');
            leftAnchor -= countOfSpecifiedChar(word, '>');
            if (leftAnchor > 0) {
                if (tempStrCache == null) {
                    tempStrCache = new ArrayList<>();
                }
                tempStrCache.add(word);
                continue;
            }
            boolean checkKeyWordNeeded = true;
            if (tempStrCache != null && tempStrCache.size() > 0) {
                String tempBuffer = "";
                for (int i = 0; i < tempStrCache.size(); i++) {
                    tempBuffer += tempStrCache.get(i);
                }
                tempBuffer += word;
                word = tempBuffer;
                tempStrCache.clear();
                checkKeyWordNeeded = false;
            }
            
            boolean matched = false;
            if (checkKeyWordNeeded) {
                for (int i = 0; i < matchSlot.length; i++) {
                    if ((processFlag & (1 << i)) == 0) {
                        matchSlot[i] = compareKeyWord(sKeywords[i], word/*, consumed*/);
                        if (matchSlot[i]) {
                            // once these keys matching success, we take access bits as matched...
                            if (i >= 7 && i <= 11) {
                                processFlag |= (1 << 0);
                                processFlag |= (1 << 1);
                                processFlag |= (1 << 2);
                            }
                            
                            processFlag |= (1 << i);
                            matched = true;
                            break;
                        }
//                        if (i == 2) {
//                            processFlag |= (1 << 0);
//                            processFlag |= (1 << 1);
//                            processFlag |= (1 << 2);
//                            // take accessLevel as default, skip to next key...
//                        }
                    }
                }
            }
            if (!matched) {
                if (unknownStr == null) {
                    unknownStr = new LinkedList<String>();
                }
                if (!"=".equals(word) && word.contains("=")) {
                    int index = word.indexOf("=");
                    if (index > 0) {
                        unknownStr.add(word.substring(0, index));
                    }
                    unknownStr.add("=");
                    if (index < word.length() - 1) {
                        unknownStr.add(word.substring(index + 1));
                    }
                } else {
                    unknownStr.add(word);
                }
            }
        }
        
        return createStatementInner(matchSlot, unknownStr, currentStatement, line);
    }

    private JavaStatement createStatementInner(boolean[] matchSlot,
            LinkedList<String> unknownStr, JavaStatement currentStatement, int line) {
        int statementType;
        if (matchSlot[7] || matchSlot[8]) {
            // "class", "interface" found
            statementType = JavaStatement.TYPE_CLASS;
        } else if (matchSlot[11]) {
//            if (currentStatement == null) {
//                // enumeration, but disguised as a class...
//                statementType = Statement.TYPE_ENUM_CLASS;
//            } else {
                // enumeration
                statementType = JavaStatement.TYPE_ENUM;
//            }
        } else if (matchSlot[12]) {
            // annotation
            statementType = JavaStatement.TYPE_ANNO;
        } else if (unknownStr == null || unknownStr.size() == 0) {
            // nothing else found
            statementType = JavaStatement.TYPE_CODE_BLOCK;
        } else {
            boolean parenthesesFound = false;
            boolean equalityFound = false;
            for (String str : unknownStr) {
                if (str.contains("(")) {
                    parenthesesFound = true;
                } else if (str.contains("=")) {
                    equalityFound = true;
                    break;
                }
            }
            // Trick: In java, method declaration must contains '()' and must not have '='...
            if (parenthesesFound && !equalityFound) {
                statementType = JavaStatement.TYPE_METHOD;
            } else {
                statementType = JavaStatement.TYPE_FIELD;
            }
        }
        if (matchSlot[13]) {
            // skip exception grammar
            unknownStr.removeLast();
        }
        
        JavaStatement statement = null;
        switch (statementType) {
        case JavaStatement.TYPE_ANNO:
            // dummy logic
            JavaStatement dummyStatement = new JavaStatement();
            dummyStatement.type = statementType;
            statement = dummyStatement;
            break;
//        case Statement.TYPE_ENUM_CLASS:
        case JavaStatement.TYPE_CLASS:
            statement = new Clazz();
            // typical structure: public static final class XXX <XXX> extends XXX implements XXX
            Clazz clazz = (Clazz) statement;
            clazz.type = statementType;
            if (matchSlot[8] && mIsAIDLMode) {
                if ("oneway".equals(unknownStr.peekFirst())) {
                    clazz.isOneway = true;
                    unknownStr.removeFirst();
                }
            }
            // extract class name
            clazz.name = unknownStr.removeFirst();
            // check templates word
            boolean[] consumed = new boolean[1];
            ArrayList<String> templates = processTemplates(unknownStr.peekFirst(), consumed);
            if (consumed[0]) {
                clazz.templates = templates;
                unknownStr.removeFirst();
            }
            // check inherited class name
            if (unknownStr.size() > 0 && matchSlot[9]) {
                clazz.extendedClazz = unknownStr.removeFirst();
            }
            // check implemented interfaces
            if (unknownStr.size() > 0 && matchSlot[10]) {
                ArrayList<String> interfaces = new ArrayList<>();
                while (unknownStr.size() > 0) {
                    String[] interfaceNames = unknownStr.removeFirst().split(",");
                    for (int i = 0; i < interfaceNames.length; i++) {
                        String interfaceName = interfaceNames[i];
                        if (interfaceName.length() > 0) {
                            interfaces.add(interfaceName);
                        }
                    }
                }
                clazz.extendedInterface = interfaces;
            }
            clazz.isInterface = matchSlot[8];
            clazz.isAbstract = matchSlot[6];
            if (currentStatement != null &&
                    currentStatement.type == JavaStatement.TYPE_CLASS) {
                clazz.isInnerClass = true;
                clazz.packageName = ((Clazz)currentStatement).packageName;
            }
            break;
        case JavaStatement.TYPE_CODE_BLOCK:
            statement = new CodeBlock();
            break;
        case JavaStatement.TYPE_FIELD:
            statement = new JavaField();
            // typical structure: public static final volatile XXX XXX = XXX
            JavaField field = (JavaField) statement;
            field.isVolatile = matchSlot[3];
            if (unknownStr.size() > 0) {
//                if (currentStatement != null && currentStatement.type ==
//                        Statement.TYPE_ENUM_CLASS) {
//                    field.feildType = "int";
//                    field.feildName = unknownStr.removeFirst();
//                    matchSlot[0] = true;
//                    matchSlot[4] = true;
//                    matchSlot[5] = true;
//                    
//                    ClassParagraph paragraph = (ClassParagraph) currentStatement.paragraph;
//                    int expectedValue;
//                    if (paragraph.staticFields == null) {
//                        expectedValue = 0;
//                    } else {
//                        expectedValue = paragraph.staticFields.size();
//                    }
//                    field.initedValue = "" + expectedValue;
//                } else {
                    field.feildType = unknownStr.removeFirst();
                    field.isAtomType = JavaField.isAtomType(field.feildType);
                    field.name = unknownStr.removeFirst();
                    if (field.name.endsWith("[]")) {
                        int suffixIndex = field.name.indexOf("[");
                        String suffix = field.name.substring(suffixIndex);
                        field.name = field.name.substring(0, suffixIndex);
                        field.feildType = field.feildType + suffix;
                    }
                    field.isArray = field.feildType.endsWith("[]");
//                }
            }
            if (unknownStr.size() > 0) {
                if ("=".equals(unknownStr.peekFirst())) {
                    unknownStr.removeFirst();
                    if ("new".equals(unknownStr.peekFirst())) {
                        unknownStr.removeFirst();
                        if (unknownStr.size() == 1) {
                            field.initedValue = "new " + unknownStr.removeFirst();
                        } else {
                            StringBuffer valueBuffer = new StringBuffer("new ");
                            while (unknownStr.size() > 0) {
                                valueBuffer.append(unknownStr.removeFirst());
                                if (unknownStr.size() > 0) {
                                    valueBuffer.append(" ");
                                }
                            }
                            field.initedValue = valueBuffer.toString();
                        }
                    } else if (unknownStr.size() > 0) {
                        if (unknownStr.size() == 1) {
                            field.initedValue = unknownStr.removeFirst();
                        } else {
                            StringBuffer initBuffer = new StringBuffer();
                            while (unknownStr.size() > 0) {
                                initBuffer.append(unknownStr.removeFirst() + " ");
                            }
                            field.initedValue = initBuffer.toString().trim();
                        }
                    }
                }
            }
            break;
        case JavaStatement.TYPE_METHOD:
            statement = new JavaMethod();
            // typical structure: public static abstract final XXX XXX(XXX...)
            JavaMethod method = (JavaMethod) statement;
            if (mIsAIDLMode) {
                if ("oneway".equals(unknownStr.peekFirst())) {
                    method.isOneway = true;
                    unknownStr.removeFirst();
                }
            }
            // check templates word
            consumed = new boolean[1];
            templates = processTemplates(unknownStr.peekFirst(), consumed);
            if (consumed[0]) {
                method.templates = templates;
                unknownStr.removeFirst();
            }
            // check if this method is construction method.
            boolean isConstruction = false;
            String firstWord = unknownStr.peekFirst();
            if (firstWord.contains("(")) {
                isConstruction = true;
            } else {
                unknownStr.removeFirst();
                String secondWord = unknownStr.peekFirst();
                if (secondWord.startsWith("(")) {
                    isConstruction = true;
                }
            }
            method.isConstruction = isConstruction;
            if (isConstruction) {
                method.name = ((Clazz)currentStatement).name;
            } else {
                method.returnType = firstWord;
                String secondWord = unknownStr.peekFirst();
                if (secondWord.contains("(")) {
                    method.name = secondWord.substring(0, secondWord.indexOf("("));
                } else {
                    method.name = secondWord;
                }
            }
            
            StringBuilder argsBuffer = null;
            String currentSplit = null;
            boolean leftSigilCaptured = false;
            boolean rightSigilCaptured = false;
            do {
                currentSplit = unknownStr.removeFirst();
                
                if (!leftSigilCaptured) {
                    if (currentSplit.contains("(")) {
                        currentSplit = currentSplit.substring(currentSplit.indexOf("(") + 1);
                        leftSigilCaptured = true;
                    }
                }
                
                if (leftSigilCaptured && !rightSigilCaptured) {
                    if (currentSplit.contains(")")) {
                        currentSplit = currentSplit.substring(0, currentSplit.indexOf(")"));
                        rightSigilCaptured = true;
                    }
                }
                
                if (currentSplit.length() > 0) {
                    if (argsBuffer == null) {
                        argsBuffer = new StringBuilder();
                    }
                    argsBuffer.append(rightSigilCaptured ? currentSplit : (currentSplit + " "));
                }
            } while (!rightSigilCaptured && unknownStr.size() > 0);
            
            if (argsBuffer != null) {
                String argStr = argsBuffer.toString().trim();
                String[] argUnits = argStr.split(",");
                ArrayList<String> argsList = new ArrayList<>();
                int leftAnchor = 0;
                String tempBuffer = "";
                for (String args : argUnits) {
                    leftAnchor += countOfSpecifiedChar(args, '<');
                    leftAnchor -= countOfSpecifiedChar(args, '>');
                    if (leftAnchor > 0) {
                        tempBuffer += args;
                        continue;
                    }
                    if (tempBuffer.length() > 0) {
                        argsList.add(tempBuffer + "," + args);
                        tempBuffer = "";
                    } else {
                        argsList.add(args.trim());
                    }
                }
                method.parameters = argsList;
            }
            method.isAbstract = matchSlot[6];
            method.isOverride = matchSlot[14];
            method.isSynchronized = matchSlot[15];
            method.isNative = matchSlot[16];
            // done
            break;
        case JavaStatement.TYPE_ENUM:
            statement = new Enumeration();
            Enumeration enumeration = (Enumeration) statement;
            enumeration.name = unknownStr.removeFirst();
            break;
        }
        
        int accessLevel = JavaStatement.LEVEL_DEFAULT;
        for (int i = 0; i < 3; i++) {
            if (matchSlot[i]) {
                // index in keyword array equals static values in Statement
                accessLevel = i;
                break;
            }
        }
        statement.accessLevel = accessLevel;
        statement.isStatic = matchSlot[4];
        statement.isFinal = matchSlot[5];
        statement.line = line;
        return statement;
    }

    private String purgeComment(final String line) {
        Matcher commentMatcherL = sDashPatternL.matcher(line);
        Matcher commentMatcherR = sDashPatternR.matcher(line);
        Matcher commentMatcherC = sDashPatternC.matcher(line);
        StringBuffer sb = null;
        int start = 0;
        int end = line.length();
        if (commentMatcherC.find()) {
            end = commentMatcherC.start();
        }
        if (end == 0) {
            return "";
        }
        int clipStart = start;
        int clipEnd = end;
        int lDashStart;
        int lDashEnd;
        boolean dashStartFound = false;
        boolean dashEndFound = false;
        outter: while (commentMatcherL.find()) {
            lDashStart = commentMatcherL.start();
            if (lDashStart >= end) {
                break;
            }
            if (lDashStart < clipStart) {
                continue;
            }
            
            dashStartFound = true;
            dashEndFound = false;
            clipEnd = lDashStart;
            lDashEnd = lDashStart;
            
            inner: while (commentMatcherR.find()) {
                int tempDashEnd = commentMatcherR.end();
                
                if (tempDashEnd > lDashStart) {
                    if (mCommentNotCompleted) {
                        // comment still alive
                        clipStart = tempDashEnd;
                        mCommentNotCompleted = false;
                        continue outter;
                    }
                    lDashEnd = tempDashEnd;
                    dashEndFound = true;
                    break inner;
                } else {
                    clipStart = tempDashEnd;
                    mCommentNotCompleted = false;
                }
            }
            
            if (clipEnd > clipStart && !mCommentNotCompleted) {
                if (sb == null) {
                    sb = new StringBuffer();
                }
                sb.append(line.substring(clipStart, clipEnd));
            }
            
            clipStart = lDashEnd;
        }
        
        if (!dashStartFound) {
            // dash start '/*' missing...
            if (mCommentNotCompleted) {
                while (commentMatcherR.find()) {
                    start = commentMatcherR.end();
                    mCommentNotCompleted = false;
                }
            }
            if (mCommentNotCompleted) {
                return "";
            }
        } else if (dashEndFound) {
            if (sb == null) {
                sb = new StringBuffer();
            }
            sb.append(line.substring(clipStart, end));
            mCommentNotCompleted = false;
        } else {
            end = clipEnd;
            mCommentNotCompleted = true;
        }
        
        if (sb == null) {
            return line.substring(start, end);
        } else {
            return sb.toString();
        }
    }
    
    private String purgeAnnotation(String line) {
        if (mAnnotationAnchor > 0) {
            for (int i = 0; i < line.length(); i++) {
                if ('(' == line.charAt(i)) {
                    mAnnotationAnchor++;
                } else if (')' == line.charAt(i)) {
                    mAnnotationAnchor--;
                    if (mAnnotationAnchor == 0) {
                        line = line.substring(i + 1);
                        break;
                    }
                }
            }
        }
        if (line.length() == 0 || mAnnotationAnchor > 0) {
            return "";
        }
        if (!line.contains("@")) {
            return line;
        }
        Matcher annoMatcher = sAnnoPattern.matcher(line);
        int clipStart = 0;
        int clipEnd = line.length();
        StringBuffer buffer = null;
        while (annoMatcher.find()) {
            int annoStart = annoMatcher.start();
            int annoEnd = annoMatcher.end();
            String currentMatch = annoMatcher.group();
            if ("@interface ".equals(currentMatch)) {
                continue;
            } else if ("@Override".equals(currentMatch)) {
                continue;
            }
            if (annoStart > clipStart) {
                if (buffer == null) {
                    buffer = new StringBuffer();
                }
                buffer.append(line.substring(clipStart, annoStart));
            }
            
            if (mAnnotationAnchor == 0 && annoEnd < line.length()) {
                if (line.charAt(annoEnd) == '(') {
                    int index = annoEnd;
                    for (int i = index; i < line.length(); i++) {
                        if ('(' == line.charAt(i)) {
                            mAnnotationAnchor++;
                        } else if (')' == line.charAt(i)) {
                            mAnnotationAnchor--;
                            if (mAnnotationAnchor == 0) {
                                annoEnd = i + 1;
                                break;
                            }
                        }
                    }
                    if (mAnnotationAnchor > 0) {
                        annoEnd = line.length();
                        clipEnd = annoStart;
                        break;
                    }
                }
            }
            
            clipStart = annoEnd;
        }
        
        if (buffer == null) {
            return line.substring(clipStart, clipEnd);
        } else {
            buffer.append(line.substring(clipStart, clipEnd));
            return buffer.toString();
        }
    }
    
    static class JavaParagraph {
        
        static final int TYPE_CLASS = 0;
        static final int TYPE_STATIC = 1;
        static final int TYPE_CONSTRUCTION = 2;
        static final int TYPE_ARRAY = 3;
        static final int TYPE_ANONYMOUS_CLASS = 4;
        static final int TYPE_METHOD = 5;
        static final int TYPE_CODE = 6;
        
        static String getTypeStr(int type) {
            switch (type) {
            case TYPE_CLASS:
                return "class";
            case TYPE_STATIC:
                return "static";
            case TYPE_CONSTRUCTION:
                return "construction";
            case TYPE_ARRAY:
                return "array";
            case TYPE_ANONYMOUS_CLASS:
                return "anonymous";
            case TYPE_METHOD:
                return "method";
            case TYPE_CODE:
                return "code";
            }
            return null;
        }
        
        int type = TYPE_CLASS;
        
        int line;
        JavaStatement owner;
        String tabS;
        
        void setHierarchyIndex(int i) {
            tabS = "\n";
            while (i-- > 0) {
                tabS += "\t";
            }
        }
        
        void addStatement(JavaStatement statement) {
        }
        
        String toSuperString() {
            return "line:" + line + " paragraphType:" + getTypeStr(type);
        }
    }

    static class ClassParagraph extends JavaParagraph {
        ArrayList<JavaField> staticFields;
        ArrayList<JavaField> fields;
        
        ArrayList<JavaMethod> staticMethods;
        ArrayList<JavaMethod> constructionMethods;
        ArrayList<JavaMethod> methods;
        
        ArrayList<Clazz> staticInnerClazzes;
        ArrayList<Clazz> innerClazzes;
        
        ArrayList<CodeBlock> staticCodeBlocks;
        ArrayList<CodeBlock> codeBlocks;
        
        ArrayList<Enumeration> enumerations;
        
        @Override
        void addStatement(JavaStatement statement) {
            if (statement != null) {
                switch (statement.type) {
                case JavaStatement.TYPE_CLASS:
//                case Statement.TYPE_ENUM_CLASS:
                    Clazz clazz = (Clazz) statement;
                    if (clazz.isStatic || clazz.isInterface) {
                        if (staticInnerClazzes == null) {
                            staticInnerClazzes = new ArrayList<>();
                        }
                        staticInnerClazzes.add(clazz);
                    } else {
                        if (innerClazzes == null) {
                            innerClazzes = new ArrayList<>();
                        }
                        innerClazzes.add(clazz);
                    }
                    break;
                case JavaStatement.TYPE_FIELD:
                    JavaField field = (JavaField) statement;
                    if (field.isStatic) {
                        if (staticFields == null) {
                            staticFields = new ArrayList<>();
                        }
                        staticFields.add(field);
                    } else {
                        if (fields == null) {
                            fields = new ArrayList<>();
                        }
                        fields.add(field);
                    }
                    break;
                case JavaStatement.TYPE_METHOD:
                    JavaMethod method = (JavaMethod) statement;
                    if (method.isStatic) {
                        if (staticMethods == null) {
                            staticMethods = new ArrayList<>();
                        }
                        staticMethods.add(method);
                    } else {
                        if (method.isConstruction) {
                            if (constructionMethods == null) {
                                constructionMethods = new ArrayList<>();
                            }
                            constructionMethods.add(method);
                        } else {
                            if (methods == null) {
                                methods = new ArrayList<>();
                            }
                            methods.add(method);
                        }
                    }
                    break;
                case JavaStatement.TYPE_CODE_BLOCK:
                    CodeBlock codeBlock = (CodeBlock) statement;
                    if (codeBlock.isStatic) {
                        if (staticCodeBlocks == null) {
                            staticCodeBlocks = new ArrayList<>();
                        }
                        staticCodeBlocks.add(codeBlock);
                    } else {
                        if (codeBlocks == null) {
                            codeBlocks = new ArrayList<>();
                        }
                        codeBlocks.add(codeBlock);
                    }
                    break;
                case JavaStatement.TYPE_ENUM:
                    Enumeration enumeration = (Enumeration) statement;
                    if (enumerations == null) {
                        enumerations = new ArrayList<>();
                    }
                    enumerations.add(enumeration);
                    break;
                }
                
            }
        }
        
        @Override
        public String toString() {
            StringBuffer buffer = new StringBuffer("ClassParagraph [" + tabS);
            buffer.append(super.toSuperString());
            if (staticFields != null) {
                buffer.append(tabS + "staticFields=" + staticFields);
            }
            if (enumerations != null) {
                buffer.append(tabS + "enumerations=" + enumerations);
            }
            if (fields != null) {
                buffer.append(tabS + "fields=" + fields);
            }
            if (staticMethods != null) {
                buffer.append(tabS + "staticMethods=" + staticMethods);
            }
            if (constructionMethods != null) {
                buffer.append(tabS + "constructionMethods=" + constructionMethods);
            }
            if (methods != null) {
                buffer.append(tabS + "methods=" + methods);
            }
            if (staticInnerClazzes != null) {
                buffer.append(tabS + "staticInnerClazzes=" + staticInnerClazzes);
            }
            if (innerClazzes != null) {
                buffer.append(tabS + "innerClazzes=" + innerClazzes);
            }
            if (staticCodeBlocks != null) {
                buffer.append(tabS + "staticCodeBlocks=" + staticCodeBlocks);
            }
            if (codeBlocks != null) {
                buffer.append(tabS + "codeBlocks=" + codeBlocks);
            }
            buffer.append(tabS + "]");
            return buffer.toString();
        }
    }

    static class JavaCodeParagraph extends JavaParagraph {
        
        private static final boolean PRINT_CODE_DETAIL = false;
        
        HashMap<Integer, String> codeByLine;
        ArrayList<String> codeByOrder;
        HashMap<Integer, JavaParagraph> paragraphByLine;
        JavaCodeReader.CodeParagraph innerCodeParagraph;
        
        void addStatement(JavaStatement statement) {
            // illegal call
        }
        
        public void setParsedCode(JavaCodeReader.CodeParagraph parsedParagraph) {
            innerCodeParagraph = parsedParagraph;
        }

        void addCode(int line, String code) {
            if (codeByLine == null) {
                codeByLine = new HashMap<>();
            }
            String oldCode = codeByLine.put(line, code);
            if (oldCode == null) {
                if (codeByOrder == null) {
                    codeByOrder = new ArrayList<>();
                }
                codeByOrder.add(code);
            }
        }
        
        @Override
        public String toString() {
            if (PRINT_CODE_DETAIL) {
                if (innerCodeParagraph != null) {
                    return innerCodeParagraph.toString();
                }
                StringBuffer buffer = new StringBuffer("CodeParagraph [");
                buffer.append(super.toSuperString());
                if (codeByLine != null) {
                    for (Entry<Integer, String> entry : codeByLine.entrySet()) {
                        buffer.append(tabS + entry.getKey() + ":" + entry.getValue());
                    }
                }
                buffer.append(tabS + "]");
                return buffer.toString();
            } else {
                return "CodeParagraph [" + super.toSuperString() + "]";
            }
        }
    }

    static class JavaStatement {
        
        static final int TYPE_CLASS = 0;
        static final int TYPE_FIELD = 1;
        static final int TYPE_CODE_BLOCK = 2;
        static final int TYPE_METHOD = 3;
        static final int TYPE_ENUM = 4;
        static final int TYPE_ANNO = 5;
//        static final int TYPE_ENUM_CLASS = 6;
        
        static final int LEVEL_PUBLIC = 0;
        static final int LEVEL_PROTECTED = 1;
        static final int LEVEL_PRIVATE = 2;
        static final int LEVEL_DEFAULT = 3;
        
        static String getAccessStr(int level) {
            switch (level) {
            case LEVEL_PUBLIC:
                return "public";
            case LEVEL_PROTECTED:
                return "protected";
            case LEVEL_PRIVATE:
                return "private";
            case LEVEL_DEFAULT:
                return "default";
            }
            return null;
        }
        
        // 0 default, 1 private, 2 protected, 3 public
        int accessLevel = LEVEL_DEFAULT;
        String name;
        
        int type;
        int line;
        JavaParagraph paragraph;
        JavaStatement parentStatement;
        ArrayList<String> relatedComment;
        boolean isAutoGenerated;
        
        boolean isStatic;
        boolean isFinal;
        String tabS;
        int hierarchyIndex;
        
        void setHierarchyIndex(int i) {
            hierarchyIndex = i;
            tabS = "\n";
            while (i-- > 0) {
                tabS += "\t";
            }
        }
        
        String toSuperString() {
            StringBuffer superBuffer = new StringBuffer("line:" + line);
            superBuffer.append(" accessLevel:" + getAccessStr(accessLevel));
            if (isStatic) {
                superBuffer.append(" isStatic=" + isStatic);
            }
            if (isFinal) {
                superBuffer.append(" isFinal=" + isFinal);
            }
            return superBuffer.toString();
        }
    }

    static class Clazz extends JavaStatement {
        String packageName;

        boolean isInnerClass;
        boolean isAbstract;
        boolean isInterface;
        
        // only available in AIDL mode
        boolean isOneway;
        
        ArrayList<String> templates;
        
        String extendedClazz;
        ArrayList<String> extendedInterface;
        
        {
            type = TYPE_CLASS;
            paragraph = new ClassParagraph();
        }
        
        @Override
        public String toString() {
            StringBuffer buffer = new StringBuffer(tabS + (isInterface ? "Interface [" : "Class ["));
            buffer.append(super.toSuperString());
            if (packageName != null) {
                buffer.append(tabS + "packageName=" + packageName);
            }
            if (name != null) {
                buffer.append(tabS + "className=" + name);
            }
            if (isOneway) {
                buffer.append(tabS + "isOneway=" + isOneway);
            }
            if (isInnerClass) {
                buffer.append(tabS + "isInnerClass=" + isInnerClass);
            }
            if (isAbstract) {
                buffer.append(tabS + "isAbstract=" + isAbstract);
            }
            if (templates != null) {
                buffer.append(tabS + "templates=" + templates);
            }
            if (extendedClazz != null) {
                buffer.append(tabS + "extendedClazz=" + extendedClazz);
            }
            if (extendedInterface != null) {
                buffer.append(tabS + "extendedInterface=" + extendedInterface);
            }
            buffer.append(tabS + paragraph);
            buffer.append(tabS + "]");
            return buffer.toString();
        }
    }

    static class JavaField extends JavaStatement {
        
        static final String ATOM_TYPE_BYTE = "byte";
        static final String ATOM_TYPE_CHAR = "char";
        static final String ATOM_TYPE_SHORT = "short";
        static final String ATOM_TYPE_INT = "int";
        static final String ATOM_TYPE_FLOAT = "float";
        static final String ATOM_TYPE_DOUBLE = "double";
        static final String ATOM_TYPE_LONG = "long";
        static final String ATOM_TYPE_BOOLEAN = "boolean";
        
        static boolean isAtomType(String type) {
            return isAtomType(type, false);
        }
        
        static boolean isAtomType(String type, boolean skipArrayCheck) {
            if (!skipArrayCheck) {
                while (type.endsWith("[]")) {
                    type = type.substring(0, type.length() - 2);
                }
            }
            if (ATOM_TYPE_BYTE.equals(type) || ATOM_TYPE_CHAR.equals(type) || 
                    ATOM_TYPE_DOUBLE.equals(type) || ATOM_TYPE_FLOAT.equals(type) || 
                    ATOM_TYPE_INT.equals(type) || ATOM_TYPE_SHORT.equals(type) ||
                    ATOM_TYPE_LONG.equals(type) || ATOM_TYPE_BOOLEAN.equals(type)) {
                return true;
            }
            return false;
        }
        
        static final ArrayList<String> sSetObjList = new ArrayList<>();
        static {
            sSetObjList.add("ArrayList");
            sSetObjList.add("LinkedList");
            sSetObjList.add("List");
            
            sSetObjList.add("HashMap");
            sSetObjList.add("ArrayMap");
            sSetObjList.add("HashTable");
            sSetObjList.add("Map");
            
            sSetObjList.add("HashSet");
            sSetObjList.add("Set");
            
            sSetObjList.add("Collection");
            
            sSetObjList.add("SparseArray");
            sSetObjList.add("ArrayMap");
            
            sSetObjList.add("Parcel");
            sSetObjList.add("StringBuffer");
            sSetObjList.add("StringBuilder");
        }
        
        static boolean isDataStructureClass(String type) {
            if (type.contains("<")) {
                int index = type.indexOf("<");
                String strBefore = type.substring(0, index);
                return sSetObjList.contains(strBefore);
            }
            return sSetObjList.contains(type);
        }
        
        boolean isAtomType;
        boolean isString;
        
        boolean isVolatile;
        boolean isArray;
        int arrayX;
        
        String feildType;
        String initedValue;
        
        {
            type = TYPE_FIELD;
        }

        @Override
        public String toString() {
            StringBuffer buffer = new StringBuffer("Field [");
            buffer.append(super.toSuperString());
            if (isAtomType) {
                buffer.append(tabS + "isAtomType=" + isAtomType);
            }
            if (isString) {
                buffer.append(tabS + "isString=" + isString);
            }
            if (isVolatile) {
                buffer.append(tabS + "isVolatile=" + isVolatile);
            }
            if (isArray) {
                buffer.append(tabS + "isArray=" + isArray);
            }
            if (feildType != null) {
                buffer.append(tabS + "feildType=" + feildType);
            }
            if (name != null) {
                buffer.append(tabS + "feildName=" + name);
            }
            if (initedValue != null) {
                buffer.append(tabS + "initedValue=" + initedValue);
            }
            if (paragraph != null) {
                buffer.append(tabS + paragraph);
            }
            buffer.append(tabS + "]");
            return buffer.toString();
        }
    }

    static class JavaMethod extends JavaStatement {
        boolean isConstruction;
        boolean isAbstract;
        boolean isOverride;
        boolean isSynchronized;
        boolean isNative;
        
        // only available in AIDL mode
        boolean isOneway;
        
        // set by JavaCodeReader
        boolean suggestIsSp;
        
        String returnType;
        
        // null if doesn't exist
        ArrayList<String> templates;
        ArrayList<String> parameters;
        
        {
            type = TYPE_METHOD;
        }
        
        @Override
        public String toString() {
            StringBuffer buffer = new StringBuffer("Method [");
            buffer.append(super.toSuperString());
            if (isConstruction) {
                buffer.append(tabS + "isConstruction=" + isConstruction);
            }
            if (isAbstract) {
                buffer.append(tabS + "isAbstract=" + isAbstract);
            }
            if (isOneway) {
                buffer.append(tabS + "isOneway=" + isOneway);
            }
            if (returnType != null) {
                buffer.append(tabS + "returnType=" + returnType);
            }
            if (name != null) {
                buffer.append(tabS + "methodName=" + name);
            }
            if (templates != null) {
                buffer.append(tabS + "templates=" + templates);
            }
            if (parameters != null) {
                buffer.append(tabS + "parameters=" + parameters);
            }
            if (paragraph != null) {
                buffer.append(tabS + paragraph);
            }
            buffer.append(tabS + "]");
            return buffer.toString();
        }
    }

    static class CodeBlock extends JavaStatement {
        {
            type = TYPE_CODE_BLOCK;
        }
        
        @Override
        public String toString() {
            StringBuffer buffer = new StringBuffer("CodeBlock [");
            buffer.append(super.toSuperString());
            buffer.append(tabS + paragraph);
            buffer.append(tabS + "]");
            return buffer.toString();
        }
    }

    static class Enumeration extends JavaStatement {
        {
            type = TYPE_ENUM;
        }
        
        @Override
        public String toString() {
            StringBuffer buffer = new StringBuffer("Enumeration [");
            buffer.append(super.toSuperString());
            if (name != null) {
                buffer.append(tabS + "enumName=" + name);
            }
            buffer.append(tabS + paragraph);
            buffer.append(tabS + "]");
            return buffer.toString();
        }
    }

    public static class JavaFile {
        String path;
        String packageName;
        ArrayList<Boolean> isImportStatic;
        ArrayList<String> importStatements;
        
        boolean isAidl;
        ArrayList<String> parcelableDeclaration;
        
        Clazz primeClass;
        ArrayList<Clazz> otherClass;// maybe null
        
        @Override
        public String toString() {
            StringBuffer fileBuffer = new StringBuffer((isAidl ? "AIDL file [" : "JavaFile [") +
                    "\n\tpath=" + path + "\n\tpackageName=" + packageName);
            if (importStatements != null && importStatements.size() > 0) {
                fileBuffer.append("\n\timportStatements=" + importStatements);
            }
            if (parcelableDeclaration != null) {
                fileBuffer.append("\n\tparcelableDeclaration=" + parcelableDeclaration);
            }
            if (primeClass != null) {
                fileBuffer.append("\n\tprimeClass=" + primeClass);
            }
            if (otherClass != null) {
                fileBuffer.append("\n\totherClass=" + otherClass);
            }
            fileBuffer.append("\t\n]");
            return fileBuffer.toString();
        }
    }
}
