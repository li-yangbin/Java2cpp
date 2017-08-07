package com.android.cplusplus;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import com.android.cplusplus.CppWriter.TypedValue;
import com.android.cplusplus.CppWriter.UnseenClassHelper;
import com.android.cplusplus.CppWriter.VAL_CATE;
import com.android.cplusplus.JavaReader.ClassParagraph;
import com.android.cplusplus.JavaReader.JavaField;
import com.android.cplusplus.JavaReader.JavaMethod;
import com.android.cplusplus.JavaReader.JavaParagraph;
import com.android.cplusplus.JavaReader.JavaStatement;
import com.android.cplusplus.JavaReader.LineParser;

/**
 * 
 * @author yangbin.li
 *
 */
public class JavaCodeReader {
    
    private static final boolean VISUALIZE_DEBUG = false;
    
    private String mLastLineBuffer;
    private final ProcessorStackImpl mStack = new ProcessorStackImpl();
    
    JavaCodeReader() {
    }
    
    static class JavaArgs {
        String type;
        String templateStr;
        String name;
        
        boolean isArray;
        int arrayDim;
        
        boolean isGlobal;
        boolean isStatic;
        boolean isRight;
        boolean isFinal;
        boolean isOutter;
        
        boolean cppTranslationProcessed;
        CppWriter.TypedValue processedVal;
        int determinedCppType;
        
        static JavaArgs obtainJavaArgs(String args, boolean methodArg) {
            int leftAnchorIndex = args.indexOf("<");
            String templatesStr = null;
            if (leftAnchorIndex >= 0) {
                int lastAnchorIndex = args.lastIndexOf(">");
                templatesStr = args.substring(leftAnchorIndex, lastAnchorIndex + 1);
                String originalStr = args;
                args = originalStr.substring(0, leftAnchorIndex) +
                        originalStr.substring(lastAnchorIndex + 1);
            }
            
            String[] argsCombo = args.split("\\s+");
            if (argsCombo == null || argsCombo.length > 3) {
                throw new RuntimeException("Invalid args format:" + args);
            }
            JavaArgs javaArgs = new JavaArgs();
            if (argsCombo.length == 1) {
                javaArgs.name = argsCombo[0];
            } else if (argsCombo.length == 2) {
                javaArgs.type = argsCombo[0];
                javaArgs.name = argsCombo[1];
            } else if (argsCombo.length == 3) {
                javaArgs.isFinal = "final".equals(argsCombo[0]);
                javaArgs.type = argsCombo[1];
                javaArgs.name = argsCombo[2];
            }
            
            javaArgs.templateStr = templatesStr;
            String typeStr = javaArgs.getTypeStr();
            if (typeStr != null) {
                javaArgs.processedVal = TypedValue.obtainCppTypedValue(javaArgs.getTypeStr(), methodArg);
                javaArgs.isArray = javaArgs.processedVal.isArray;
            }
            
            return javaArgs;
        }
        
        @Override
        public String toString() {
            StringBuffer buffer = new StringBuffer();
            if (!cppTranslationProcessed && isFinal) {
                buffer.append("final ");
            }
            String typeStr;
            if (cppTranslationProcessed && processedVal != null) {
                typeStr = processedVal.toString();
            } else {
                typeStr = type;
            }
            buffer.append(typeStr + " " + name);
            
            return buffer.toString();
        }
        
        void collectClassName(UnseenClassHelper helper) {
            if (processedVal != null) {
                processedVal.collectClassName(helper);
            }
        }
        
        boolean isSp() {
            return processedVal != null && processedVal.isSp;
        }
        
        boolean isPointer() {
            return processedVal != null && processedVal.type == VAL_CATE.POINTER;
        }
        
        void onTranslationProcessing() {
            cppTranslationProcessed = true;
        }
        
        String getTypeStr() {
            if (templateStr != null) {
                return type + templateStr;
            } else {
                return type;
            }
        }
    }

    public void start(JavaParagraph javaParagraph) {
        if (mStack.pick() != null) {
            throw new RuntimeException("top is exist:" + mStack.pick());
        }
        mLastLineBuffer = null;
        JavaStatement statement = javaParagraph.owner;
        switch (statement.type) {
        case JavaStatement.TYPE_METHOD:
        case JavaStatement.TYPE_CODE_BLOCK:
            break;
        default:
            throw new RuntimeException("Invalid statement:" + statement);
        }
        RootParagraph root = new RootParagraph(statement);
        CodeParagraph codeParagraph = new CodeParagraph(root);
        if (statement.type == JavaStatement.TYPE_METHOD) {
            JavaReader.JavaMethod method = (JavaReader.JavaMethod) statement;
            if (method.parameters != null) {
                for (int i = 0; i < method.parameters.size(); i++) {
                    String parameter = method.parameters.get(i);
                    codeParagraph.addVariable(JavaArgs.obtainJavaArgs(parameter.trim(), true));
                }
            }
        }
        codeParagraph.checkSelfBranceSuppressed = true;
        mStack.push(codeParagraph);
    }
    
    public CodeParagraph finish() {
        int counter = 0;
        ICodeProcessor current = mStack.pick();
        ICodeProcessor lastOne = null;
        while (current != null) {
            lastOne = current;
            if (mLastLineBuffer != null && mLastLineBuffer.length() > 0) {
                mLastLineBuffer = mStack.process(mLastLineBuffer);
                
                if (current != mStack.pick()) {
                    counter = 0;
                } else {
                    counter++;
                    // every target has 2 more chances to handle unfinished code
                    if (counter >= 2) {
                        mStack.pop();
                        counter = 0;
                    }
                }
            } else {
                mStack.pop();
            }
            current = mStack.pick();
        }
        return (CodeParagraph) lastOne;
    }
    
    interface ICodeProcessor {
        String processCodeLine(ProcessorStack stack, String code);
        ICodeProcessor getPrev();
        void setPrev(ICodeProcessor processor);
    }
    
    interface ProcessorStack {
        void push(ICodeProcessor processor);
        ICodeProcessor pop();
        ICodeProcessor pick();
    }
    
    static int indexOfSigil(String target, char sigil) {
        int length = target.length();
        int quoCount = 0;
        boolean quotationMet = false;
        boolean singleQuoteMet = false;
        for (int i = 0; i < length; i++) {
            char charactor = target.charAt(i);
            if (!singleQuoteMet && charactor == '"') {
                quotationMet = !quotationMet;
            } else if (!quotationMet && charactor == '\'') {
                singleQuoteMet = !singleQuoteMet;
            } else if (!quotationMet && !singleQuoteMet && charactor == sigil && quoCount % 2 == 0) {
                return i;
            }
        }
        return -1;
    }
    
    static int startsWith(String str, char prefix) {
        int length = str.length();
        for (int i = 0; i < length; i++) {
            char charactor = str.charAt(i);
            if (charactor == ' ') {
                continue;
            } else if (charactor == prefix) {
                return i;
            } else {
                return -1;
            }
        }
        return -1;
    }
    
    static int endsWith(String str, char suffix) {
        int length = str.length();
        for (int i = length - 1; i >= 0; i--) {
            char charactor = str.charAt(i);
            if (charactor == ' ') {
                continue;
            } else if (charactor == suffix) {
                return i;
            } else {
                return -1;
            }
        }
        return -1;
    }
    
    static boolean isEmptyOrSpace(String str, int start, int end) {
        if (str == null || str.length() == 0) {
            return true;
        }
        for (int i = start; i < end; i++) {
            if (str.charAt(i) != ' ') {
                return false;
            }
        }
        return true;
    }
    
    class ProcessorStackImpl implements ProcessorStack {
        
        ICodeProcessor top;
        boolean deliverTargetChanged;
        int size;
        
        String process(String code) {
            String surplus = code;
            deliverTargetChanged = true;
            while (surplus != null && surplus.length() > 0 && deliverTargetChanged && top != null) {
                deliverTargetChanged = false;
                surplus = top.processCodeLine(this, surplus);
            }
            return surplus;
        }
        
        @Override
        public void push(ICodeProcessor processor) {
            processor.setPrev(top);
            top = processor;
            deliverTargetChanged = true;
            if (VISUALIZE_DEBUG) {
                String space = "";
                for (int i = 0; i < size; i++) {
                    space += "    ";
                }
                System.out.println(space + "push in:" + processor.getClass().getSimpleName() +
                        ":" + System.identityHashCode(processor));
            }
            size++;
        }
        
        @Override
        public ICodeProcessor pop() {
            ICodeProcessor oldTop = top;
            top = top != null ? top.getPrev() : null;
            deliverTargetChanged = true;
            if (oldTop != null) {
                size--;
                if (VISUALIZE_DEBUG) {
                    String space = "";
                    for (int i = 0; i < size; i++) {
                        space += "    ";
                    }
                    System.out.println(space + "pop out:" + oldTop.getClass().getSimpleName() +
                            ":" + System.identityHashCode(oldTop));
                }
            }
            return oldTop;
        }
        
        @Override
        public ICodeProcessor pick() {
            return top;
        }
    }
    
    public void processCodeLine(String code) {
        if (mLastLineBuffer != null) {
            code = mLastLineBuffer + " " + code;
        }
        mLastLineBuffer = mStack.process(code);
    }
    
    public static void printAndClearCodeParserRecord() {
        if (CodeParser.sProcessingCount != 0) {
            System.out.println("Total code statement processing count:" + CodeParser.sProcessingCount);
            int averageCount = CodeParser.sTotalRecursiveDepth / CodeParser.sProcessingCount;
            System.out.println("Average code statement recursive depth:" + averageCount);
            System.out.println("Maximum code statement recursive depth:" + CodeParser.sMaximumRecursiveDepth);
        } else {
        }
        
        CodeParser.sProcessingCount = 0;
        CodeParser.sMaximumRecursiveDepth = 0;
        CodeParser.sTotalRecursiveDepth = 0;
    }
    
    static class CodeParser {
        String code;
        CodeParagraph paragraph;
        int[] tmp = new int[2];
        
        static int sProcessingCount;
        static int sTotalRecursiveDepth;
        static int sMaximumRecursiveDepth;
        
        static CodeParser sPool;
        static int sPoolSize;
        
        CodeParser next;
        
        static CodeStatement parseCode(CodeParagraph paragraph, String code) {
            if (code == null || isEmptyOrSpace(code, 0, code.length())) {
                return null;
            }
            CodeParser parser = CodeParser.obtain(paragraph, code);
            try {
                sProcessingCount++;
                return parser.process();
            } /*catch (Exception e) {
                e.printStackTrace();
                return null;
            }*/ finally {
                if (sMaximumRecursiveDepth < parser.recursiveDepth) {
                    sMaximumRecursiveDepth = parser.recursiveDepth;
                }
                sTotalRecursiveDepth += parser.recursiveDepth;
                parser.recycle();
            }
        }

        static CodeParser obtain(CodeParagraph paragraph, String code) {
            if (sPool == null) {
                return new CodeParser(paragraph, code);
            }
            CodeParser out = sPool;
            out.setCode(code);
            out.paragraph = paragraph;
            sPool = out.next;
            sPoolSize--;
            return out;
        }
        
        private CodeParser(CodeParagraph paragraph, String code) {
            setCode(code);
            this.paragraph = paragraph;
        }
        
        void recycle() {
            code = null;
            paragraph = null;
            pendingLength = 0;
            codeLineIndex = 0;
            resolvedStatement = null;
            opIndex = null;
            recursiveDepth = 0;
            if (pendingStatement != null) {
                Arrays.fill(pendingStatement, null);
            }
            
            if (sPoolSize < 5) {
                next = sPool;
                sPool = this;
                sPoolSize++;
            }
        }
        
        void setCode(String code) {
            this.code = code;
        }
        
        int[] opIndex;
        CodeStatement[] pendingStatement;
        CodeStatement resolvedStatement;
        int pendingLength;
        int recursiveDepth;
        int codeLineIndex;
        
        void addPendingStatement(CodeStatement  statement) {
            int currentLength = pendingStatement != null ? pendingStatement.length : 0;
            if (currentLength <= pendingLength) {
                CodeStatement[] oldArray = pendingStatement;
                pendingStatement = new CodeStatement[currentLength + 4];
                if (oldArray != null) {
                    System.arraycopy(oldArray, 0, pendingStatement, 0, oldArray.length);
                }
            }
            pendingStatement[pendingLength++] = statement;
        }
        
//        void removePendingStatementAt(int index) {
//            if (index >= PENDING_RESOLVE) {
//                index -= PENDING_RESOLVE;
//            }
//            if (index < pendingLength || index >= pendingLength) {
//                return;
//            }
//            pendingStatement[index] = null;
//            for (int i = index + 1; i < pendingLength; i++) {
//                pendingStatement[i - 1] = pendingStatement[i];
//            }
//            pendingStatement[pendingLength - 1] = null;
//            pendingLength--;
//        }
        
        CodeStatement getPendingStatementAt(int index) {
            if (index >= PENDING_RESOLVE) {
                index -= PENDING_RESOLVE;
            }
            if (index < 0 || index >= pendingLength) {
                return null;
            }
            return pendingStatement[index];
        }
        
        CodeStatement process() {
            if (resolvedStatement == null) {
                prepareStatementProcessing();
                codeLineIndex = paragraph.appendCodeLine();
                resolvedStatement = processNextStatement(0, code.length(),
                        RESOLVE_POLICY_COMMON);
            }
            return resolvedStatement;
        }
        
        CodeStatement processNextStatement(int start, int end, int policy) {
            if (start >= end) {
                return null;
            }
            CodeStatement statement;
            boolean resolveSuccess;
            do {
                resolveSuccess = true;
                
                statement = processNextStatementInner(start, end, policy);
                
                if (statement != null && policy == RESOLVE_POLICY_COMMON) {
                    int resolvedStart = statement.startIndex;
                    int resolvedEnd = statement.endIndex;
                    
                    boolean pendingResolve;
                    if (resolvedStart == start && resolvedEnd == end) {
                        pendingResolve = false;
                    } else if (resolvedStart >= start && resolvedEnd <= end) {
                        pendingResolve = true;
                    } else {
                        throw new RuntimeException("Logic crash. start:" + start + " end:" + end +
                                " resolvedStart:" + resolvedStart + " resolvedEnd:" + resolvedEnd + " code:" + code);
                    }
                    markAsResolved(statement, resolvedStart, resolvedEnd, pendingResolve);
                    
                    boolean bracketPair = false;
                    switch (statement.type) {
                    case TYPE_OP_RIGHT_BRACKET:
                    case TYPE_OP_RIGHT_BRACKET_I:
                    case TYPE_OP_RIGHT_BRACKET_II:
                        // left bracket find us, abort
                        bracketPair = true;
                        break;
                    }
                    if (!bracketPair) {
                        for (int i = start; i < end; i++) {
                            if (opIndex[i] != RESOLVED && opIndex[i] != SPACE) {
                                resolveSuccess = false;
                                break;
                            }
                        }
                    }
                }

            } while (!resolveSuccess);
            return statement;
        }
        
        CodeStatement processNextStatementInner(int start, int end, int policy) {
            recursiveDepth++;
            int topPrioOp = Integer.MAX_VALUE;
            int index = -1;
            switch (policy) {
            case RESOLVE_POLICY_COMMON:
                for (int i = start; i < end; i++) {
                    int flag = opIndex[i];
                    if (flag > UNRESOLVE && flag < RESOLVED) {
                        boolean matchMostRight = false;
                        switch (topPrioOp) {
                          case TYPE_OP_LEFT_BRACKET:
                          case TYPE_OP_LEFT_BRACKET_I:
                          case TYPE_OP_LEFT_BRACKET_II:
                              matchMostRight = true;
                              break;
                          }
                        if (flag < topPrioOp || (matchMostRight && flag == topPrioOp)) {
                            topPrioOp = flag;
                            index = i;
                        }
                    }
                }
                break;
            case RESOLVE_POLICY_L2RASAP:
                for (int i = start; i < opIndex.length; i++) {
                    int flag = opIndex[i];
                    if (flag > UNRESOLVE && flag < RESOLVED) {
                        end = i;
                        break;
                    }
                }
                break;
            case RESOLVE_POLICY_R2LASAP:
                for (int i = end - 1; i >= 0; i--) {
                    int flag = opIndex[i];
                    if (flag > UNRESOLVE && flag < RESOLVED) {
                        start = i + 1;
                        break;
                    }
                }
                break;
            }
            if (topPrioOp == Integer.MAX_VALUE) {
                // resolve failed
                if (end > start && start >= 0 && !isEmptyOrSpace(code, start, end)) {
                    int noSpaceIndex;
                    if (policy == RESOLVE_POLICY_L2RASAP || policy == RESOLVE_POLICY_COMMON) {
                        noSpaceIndex = start;
                        while (opIndex[noSpaceIndex] == SPACE) {
                            noSpaceIndex++;
                        }
                    } else /*if (index == RESOLVE_POLICY_R2LASAP)*/ {
                        noSpaceIndex = end - 1;
                        while (opIndex[noSpaceIndex] == SPACE) {
                            noSpaceIndex--;
                        }
                    }
                    CodeStatement statement;
                    if (opIndex[noSpaceIndex] == RESOLVED) {
                        throw new RuntimeException("Logic crash. start:" + start + " end:" + end + " noSpaceIndex:" + noSpaceIndex);
                    } else if (opIndex[noSpaceIndex] >= PENDING_RESOLVE) {
                        statement = getPendingStatementAt(opIndex[noSpaceIndex]);
//                        if (opIndex[end] != opIndex[start]) {
//                            throw new RuntimeException("Logic crash. start:" + start + "end:" + end);
//                        }
                    } else /*if (opIndex[noSpaceIndex] == UNRESOLVE)*/ {
                        statement = Expression.obtainExpression(code, start, end);
                        statement.index = codeLineIndex;
                        statement.parentParagraph = paragraph;
                        statement.startIndex = start;
                        statement.endIndex = end;
                        statement.onCreate(paragraph);
                    }
                    return statement;
                }
                return null;
            }
            
            final Op opInfo = sOpMap[topPrioOp];
            if (opInfo != null) {
                final int opLength = opInfo.op.length;
                
                CodeStatement previous = null;
                if (opInfo.leftPolicy == 2) {
                    previous = processNextStatement(start, index, RESOLVE_POLICY_COMMON);
                } else if (opInfo.leftPolicy == 1) {
                    previous = processNextStatement(start, index, RESOLVE_POLICY_R2LASAP);
                }
                
                CodeStatement next = null;
                if (opInfo.rightPolicy == 2) {
                    next = processNextStatement(index + opLength, end, RESOLVE_POLICY_COMMON);
                } else if (opInfo.rightPolicy == 1) {
                    next = processNextStatement(index + opLength, end, RESOLVE_POLICY_L2RASAP);
                }
                
                Operator op = Operator.obtainOperator(topPrioOp, previous, next);
                op.index = codeLineIndex;
                op.prev = previous;
                op.next = next;
                op.parentParagraph = paragraph;
                if (previous != null) {
                    op.startIndex = previous.startIndex;
                } else {
                    int includeSpaceStart = index;
                    while (includeSpaceStart > start && opIndex[includeSpaceStart - 1] == SPACE) {
                        includeSpaceStart--;
                    }
                    op.startIndex = includeSpaceStart;
                }
                if (next != null) {
                    op.endIndex = next.endIndex;
                } else {
                    int includeSpaceEnd = index + opLength;
                    while (includeSpaceEnd < end && opIndex[includeSpaceEnd] == SPACE) {
                        includeSpaceEnd++;
                    }
                    op.endIndex = includeSpaceEnd;
                }
                op.onCreate(paragraph);
                
                return op;
            }
            throw new RuntimeException("Logic crash. topOp:" + topPrioOp);
        }
        
        void markAsResolved(CodeStatement statement, int start, int end, boolean pending) {
            for (int i = start; i < end; i++) {
                if (!pending) {
                    opIndex[i] = RESOLVED;
                } else {
                    int pendingIndex = pendingLength;
                    opIndex[i] = PENDING_RESOLVE + pendingIndex;
                }
            }
            if (pending) {
                addPendingStatement(statement);
            }
        }
        
        void prepareStatementProcessing() {
            int length = code.length();
            opIndex = new int[length];
            Arrays.fill(opIndex, UNRESOLVE);
            
            boolean quotationMet = false;
            boolean singleQuoteMet = false;
            boolean lastIsSlash = false;
            int lastLeftMidBracketIndex = -1;
            int skipNext = 0;
            ArrayList<Integer> bracketIndexStack = null;
//            int lastAngleBracketIndex = -1;
            for (int i = 0; i < length; i++) {
                if (skipNext > 0) {
                    skipNext--;
                    continue;
                }
                char character = code.charAt(i);
                if (character == ' ') {
                    opIndex[i] = (singleQuoteMet || quotationMet) ? UNRESOLVE : SPACE;
                    continue;
                }
                if (!lastIsSlash && !singleQuoteMet && character == '"') {
                    quotationMet = !quotationMet;
                    continue;
                } else if (!lastIsSlash && !quotationMet && character == '\'') {
                    singleQuoteMet = !singleQuoteMet;
                    continue;
                } else if (quotationMet || singleQuoteMet) {
                    lastIsSlash = !lastIsSlash && character == '\\';
                    continue;
                }
                boolean matchFound = false;
                for (int j = 0; j < sOpMap.length; j++) {
                    char[] op = sOpMap[j].op;
                    boolean matchSuccess = true;
                    for (int k = 0; k < op.length; k++) {
                        if (i + k >= length) {
                            matchSuccess = false;
                            break;
                        }
                        char nextChar = code.charAt(i + k);
                        if (nextChar != op[k]) {
                            matchSuccess = false;
                            break;
                        }
                    }
                    
                    if (!matchSuccess) {
                        continue;
                    }
                    matchFound = true;
                    
                    if (j == TYPE_OP_ASSIGN) {
                        // special case: see if it is a "==" operator
                        if (i + 1 < length && code.charAt(i + 1) == '=') {
                            // yes it is
                            j = TYPE_OP_EQUAL;
                            op = sOpMap[j].op;
                        }
                    }
                    skipNext = op.length - 1;
                    
                    for (int k = 0; k < op.length; k++) {
                        opIndex[i + k] = j;
                    }
//                    matchSuccess = true;
                    if (j == TYPE_OP_LESSER) {
                        if (bracketIndexStack == null) {
                            bracketIndexStack = new ArrayList<>();
                        }
                        bracketIndexStack.add(0, i);
//                        lastAngleBracketIndex = i;
                    } else if (j == TYPE_OP_INSTANCEOF) {
                        boolean yesitis = false;
                        if (i - 1 >= 0 && i + op.length < length) {
                            if (code.charAt(i - 1) == ' ') {
                                if (code.charAt(i + op.length) == ' ') {
                                    yesitis = true;
                                }
                            }
                        }
                        if (!yesitis) {
                            for (int k = 0; k < op.length; k++) {
                                opIndex[i + k] = UNRESOLVE;
                            }
                        }
                    } else if (j == TYPE_OP_MINUS || j == TYPE_OP_INVOKE) {
                        if (i + 1 < length && Character.isDigit(code.charAt(i + 1))) {
                            opIndex[i] = UNRESOLVE;
                        }
                    } else if (j == TYPE_OP_MORE && bracketIndexStack != null &&
                            bracketIndexStack.size() > 0) {
                        // check to see if it is a template statement
                        boolean yesItis = true;
                        int topIndex = bracketIndexStack.get(0);
                        for (int k = topIndex + 1; k < i; k++) {
                            int flag = opIndex[k];
                            if (flag == TYPE_OP_COMMA || flag == TYPE_OP_QUES_MARK/*
                                    || flag == TYPE_OP_LESSER || flag == TYPE_OP_MORE*/) {
                                continue;
                            }
                            if (flag > UNRESOLVE && flag < RESOLVED) {
                                yesItis = false;
                                break;
                            }
                        }
                        if (yesItis) {
                            // :(
                            for (int k = topIndex; k <= i; k++) {
                                opIndex[k] = UNRESOLVE;
                            }
//                            matchSuccess = false;
                        }
                        bracketIndexStack.remove(0);
                    } else if (j == TYPE_OP_RIGHT_BRACKET_I && lastLeftMidBracketIndex >= 0) {
                        boolean isOp = false;
                        for (int k = i + 1; k < length; k++) {
                            char nextChar = code.charAt(k);
                            if (nextChar == ' ') {
                                continue;
                            } else if (nextChar == '{') {
                                isOp = true;
                                break;
                            } else {
                                break;
                            }
                        }
                        if (!isOp) {
                            opIndex[i] = UNRESOLVE;
                            opIndex[lastLeftMidBracketIndex] = UNRESOLVE;
                        }
//                        matchSuccess = false;
                    }
                    
                    if (j == TYPE_OP_LEFT_BRACKET_I) {
                        lastLeftMidBracketIndex = i;
                    } else if (lastLeftMidBracketIndex >= 0) {
                        lastLeftMidBracketIndex = -1;
                    }
                    
                    break;
                }
                if (!matchFound && lastLeftMidBracketIndex >= 0) {
                    lastLeftMidBracketIndex = -1;
                }
            }
        }
        
        static final int SPACE = -2;
        static final int UNRESOLVE = -1;
        static final int RESOLVED = 1000;
        static final int PENDING_RESOLVE = 10000;
        
        static final int RESOLVE_POLICY_COMMON = 1;
        static final int RESOLVE_POLICY_L2RASAP = 2;
        static final int RESOLVE_POLICY_R2LASAP = 3;
        static final int RESOLVE_POLICY_NONE = 0;
    }
    
    static class Op {
        char[] op;
        int leftPolicy;
        int rightPolicy;
        
        static Op obtain(int leftPolicy, int rightPolicy, char... opArray) {
            Op op = new Op();
            op.op = opArray;
            op.leftPolicy = leftPolicy;
            op.rightPolicy = rightPolicy;
            return op;
        }
    }
    
    static final Op[] sOpMap;
    static {
        sOpMap = new Op[]{
                Op.obtain(1, 2, '('), Op.obtain(2, 1, ')'),
                Op.obtain(1, 2, '{'), Op.obtain(2, 0, '}'),
                Op.obtain(1, 2, '['), Op.obtain(2, 1, ']'),
                Op.obtain(2, 2, ','),
                Op.obtain(2, 2, '='), // special case in terms of "=="
                Op.obtain(2, 2, '+', '='),
                Op.obtain(2, 2, '-', '='),
                Op.obtain(2, 2, '*', '='),
                Op.obtain(2, 2, '/', '='),
                Op.obtain(2, 2, '%', '='),
                Op.obtain(2, 2, '&', '='),
                Op.obtain(2, 2, '|', '='),
                Op.obtain(2, 2, '?'),
                Op.obtain(2, 2, ':'),
                Op.obtain(1, 1, '.'),
                Op.obtain(2, 2, 'i', 'n', 's', 't', 'a', 'n', 'c', 'e', 'o', 'f'),
                Op.obtain(1, 1, '+', '+'),
                Op.obtain(1, 1, '-', '-'),
                Op.obtain(2, 2, '&', '&'), Op.obtain(2, 2, '&'),
                Op.obtain(2, 2, '|', '|'), Op.obtain(2, 2, '|'),
                Op.obtain(2, 2, '=', '='),
                Op.obtain(2, 2, '!', '='),
                Op.obtain(0, 1, '!'),
                Op.obtain(2, 2, '>', '='), Op.obtain(2, 2, '>'),
                Op.obtain(2, 2, '<', '='), Op.obtain(2, 2, '<'),
                Op.obtain(2, 2, '+'),
                Op.obtain(2, 2, '-'),
                Op.obtain(2, 2, '*'),
                Op.obtain(2, 2, '/'),
                Op.obtain(2, 2, '%')};
    }
    
    static final int TYPE_STATEMENT_DUMMY = -6;
    static final int TYPE_STATEMENT_PARAGRAPH_WRAPPER = -5;
    
    static final int TYPE_STATEMENT_DECLARATION = -4;
    static final int TYPE_STATEMENT_ARRAY = -3;
    static final int TYPE_STATEMENT_METHOD = -2;
    static final int TYPE_STATEMENT_EXPRESSION = -1;
    static final int TYPE_OP_LEFT_BRACKET = 0;
    static final int TYPE_OP_RIGHT_BRACKET = 1;
    static final int TYPE_OP_LEFT_BRACKET_II = 2;
    static final int TYPE_OP_RIGHT_BRACKET_II = 3;
    static final int TYPE_OP_LEFT_BRACKET_I = 4;
    static final int TYPE_OP_RIGHT_BRACKET_I = 5;
    static final int TYPE_OP_COMMA = 6;
    static final int TYPE_OP_ASSIGN = 7;
    static final int TYPE_OP_PLUS_ASSIGN = 8;
    static final int TYPE_OP_MINUS_ASSIGN = 9;
    static final int TYPE_OP_MUTI_ASSIGN = 10;
    static final int TYPE_OP_DIVI_ASSIGN = 11;
    static final int TYPE_OP_REMAIN_ASSIGN = 12;
//    static final int TYPE_OP_AND_ASSIGN = 13;
//    static final int TYPE_OP_OR_ASSIGN = 14;
    static final int TYPE_OP_QUES_MARK = 15;
    static final int TYPE_OP_COLON = 16;
    static final int TYPE_OP_INVOKE = 17;
    static final int TYPE_OP_INSTANCEOF = 18;
    static final int TYPE_OP_EQUAL = 25;
    static final int TYPE_OP_NOT_EQUAL = 26;
    static final int TYPE_OP_MORE = 29;
    static final int TYPE_OP_LESSER = 31;
    static final int TYPE_OP_PLUS = 32;
    static final int TYPE_OP_MINUS = 33;
    static final int TYPE_OP_REMINDER = 36;
    
    static final String[] sKeyWord = {
            "if", /*"else if", "else",*/
            "for",
            "do",
            "while",
            "switch", "case", "default",
            "try", /*"catch", "finally",*/
            "synchronized",
            /*"new",*/
            "return", "continue", "break", "throw"};
    
    static final int TYPE_KEY_IF = 0;
    static final int TYPE_KEY_FOR = 1;
    static final int TYPE_KEY_DO_WHILE = 2;
    static final int TYPE_KEY_WHILE = 3;
    static final int TYPE_KEY_SWITCH = 4;
    static final int TYPE_KEY_CASE = 5;
    static final int TYPE_KEY_DEFAULT = 6;
    static final int TYPE_KEY_TRY_CATCH = 7;
    static final int TYPE_KEY_SYNC = 8;
    static final int TYPE_KEY_RETURN = 9;
    static final int TYPE_KEY_CONTINUE = 10;
    static final int TYPE_KEY_BREAK = 11;
    static final int TYPE_KEY_THROW = 12;
    
    private static class CodeStatement {
        static final int CPP_TYPE_THIS = -2;
        static final int CPP_TYPE_POINTER = -1;
        
        static final int CPP_TYPE_NONE = 0;
        
        static final int CPP_TYPE_REF = 1;
        static final int CPP_TYPE_COMMON_EXP = 2;
        static final int CPP_TYPE_ARRAY = 3;
        static final int CPP_TYPE_ATOM = 4;
        static final int CPP_TYPE_DATA_SET = 5;
        static final int CPP_TYPE_STRING = 6;
        
        boolean cppTranslationProcessed;
        CodeParagraph parentParagraph;
        int type;
        int index;
        
        // used for code parsing
        int startIndex;
        int endIndex;
        //
        
        CodeStatement(int type) {
            this.type = type;
        }
        
        void onCreate(CodeParagraph paragraph) {
        }
        
        void write(String prefix, PrintStream out) {
            out.println(prefix + this.toString() + ";");
        }
        
        void dispatchTranslation(UnseenClassHelper helper) {
            cppTranslationProcessed = true;
            onTranslationProcessing(helper);
        }

        protected void onTranslationProcessing(UnseenClassHelper helper) {
        }
        
        int resolveCppValType() {
            return CPP_TYPE_NONE;
        }
    }
    
    private static class Operator extends CodeStatement {
        
        static final int VALUEOF_PLUS_NONE = 0;
        static final int VALUEOF_PLUS_LEFT = 1;
        static final int VALUEOF_PLUS_RIGHT = 2;
        
        CodeStatement prev;
        CodeStatement next;
        
        boolean refEqualTranslated;
        int plusValueFlag = VALUEOF_PLUS_NONE;
        
        static Operator obtainOperator(int type, CodeStatement previous,
                CodeStatement next) {
            Operator operator = null;
            switch (type) {
            case TYPE_OP_LEFT_BRACKET:
            case TYPE_OP_LEFT_BRACKET_I:
                if (previous != null) {
                    if (previous.type == TYPE_STATEMENT_EXPRESSION) {
                        Expression exp = (Expression) previous;
                        return type == TYPE_OP_LEFT_BRACKET ? new Method(exp) : new Array(exp);
                    } else {
//                        throw new RuntimeException("Invalid left value:" + previous);
                    }
                }
                // fall through
            case TYPE_OP_LEFT_BRACKET_II:
                if (next != null && next.type == type + 1) {
                    Operator pairedBracket = (Operator) next;
                    if (pairedBracket.next != null) {
                        return new CastOp(type);
                    } else {
                        return new Bracket(type);
                    }
                } else {
                    throw new RuntimeException("Bracket operator must matched with an" +
                            " another bracket operator. next:" + next);
                }
            case TYPE_OP_INVOKE:
                return new Invocation();
            case TYPE_OP_INSTANCEOF:
                return new Instanceof();
            case TYPE_OP_ASSIGN:
                return new Assign();
            default:
                operator = new Operator(type);
            }
            
            return operator;
        }

        Operator(int type) {
            super(type);
        }
        
        @Override
        public String toString() {
            Op opInfo = sOpMap[type];
            StringBuffer buffer = new StringBuffer();
            if (prev != null) {
                if (plusValueFlag == VALUEOF_PLUS_LEFT) {
                    buffer.append("String::valueOf(" + prev.toString() + ")");
                } else {
                    buffer.append(prev.toString());
                }
            }
            if (refEqualTranslated) {
                buffer.append(".size() > 0");
                return buffer.toString();
            }
            boolean leftSpace = prev != null && opInfo.leftPolicy == 2;
            if (leftSpace) {
                buffer.append(" ");
            }
            if (!onPrintOp(buffer)) {
                buffer.append(opInfo.op);
            }
            boolean rightSpace = next != null && opInfo.rightPolicy == 2;
            if (rightSpace) {
                buffer.append(" ");
            }
            if (next != null) {
                if (plusValueFlag == VALUEOF_PLUS_RIGHT) {
                    buffer.append("String::valueOf(" + next.toString() + ")");
                } else {
                    buffer.append(next.toString());
                }
            }
            return buffer.toString();
        }
        
        boolean onPrintOp(StringBuffer buffer) {
            return false;
        }
        
        @Override
        void dispatchTranslation(UnseenClassHelper helper) {
            if (prev != null) {
                prev.dispatchTranslation(helper);
            }
            super.dispatchTranslation(helper);
            if (next != null) {
                next.dispatchTranslation(helper);
            }
        }
        
        @Override
         protected void onTranslationProcessing(UnseenClassHelper helper) {
            if (type == TYPE_OP_EQUAL || type == TYPE_OP_NOT_EQUAL) {
                if (prev != null && next != null && next.type == TYPE_STATEMENT_EXPRESSION) {
                    Expression exp = (Expression) next;
                    if (exp.isNull) {
                        if (prev.resolveCppValType() == CPP_TYPE_DATA_SET
                                || prev.resolveCppValType() == CPP_TYPE_ARRAY) {
                            refEqualTranslated = true;
                        }
                    }
                }
            }
            
            if ((type >= TYPE_OP_PLUS && type <= TYPE_OP_REMINDER) || 
                    type == TYPE_OP_COLON || 
                    (type >= TYPE_OP_PLUS_ASSIGN && type <= TYPE_OP_REMAIN_ASSIGN)) {
                if (prev != null && next != null) {
                    int leftType = prev.resolveCppValType();
                    int rightType = next.resolveCppValType();
                    boolean leftIsString = leftType == CPP_TYPE_STRING;
                    boolean rightIsString = rightType == CPP_TYPE_STRING;
                    if (leftIsString && !rightIsString) {
                        if (rightType == CPP_TYPE_ATOM) {
                            plusValueFlag = VALUEOF_PLUS_RIGHT;
                        }
                    } else if (!leftIsString && rightIsString) {
                        if (leftType == CPP_TYPE_ATOM) {
                            plusValueFlag = VALUEOF_PLUS_LEFT;
                        }
                    }
                }
            }
         }
    }
    
    private static class DummyStatement extends CodeStatement {
        
//        final static DummyStatement sEmptyLine = new DummyStatement("");
        
        String dummyCode;

        DummyStatement(String dummyCode) {
            super(TYPE_STATEMENT_DUMMY);
            this.dummyCode = dummyCode;
        }
        
        @Override
        public String toString() {
            return dummyCode;
        }
    }
    
    private static class CodeParagraphWrapper extends CodeStatement {
        
        CodeParagraph paragraph;

        CodeParagraphWrapper(CodeParagraph paragraph) {
            super(TYPE_STATEMENT_PARAGRAPH_WRAPPER);
            this.paragraph = paragraph;
        }
        
        @Override
        void write(String prefix, PrintStream out) {
            paragraph.write(prefix, out);
        }
        
        @Override
        void dispatchTranslation(UnseenClassHelper helper) {
            super.dispatchTranslation(helper);
            paragraph.dispatchTranslation(helper);
        }
        
        @Override
        public String toString() {
            return paragraph.toString();
        }
    }
    
    private static class Expression extends CodeStatement {
        String expression;
        String expName;
        boolean isConstruction;
        JavaArgs resolvedVariable;
        boolean variableResolved;
        boolean resolveVariableLocally;
        boolean wholeSearch;
        
        boolean isNull;
        boolean isAppendGetRefBySpVariable;
        boolean outterWarningSuppressed;
        
        static Expression obtainExpression(String code, int start, int end) {
            final String trimedStr = code.substring(start, end).trim();
            final int length = trimedStr.length();
            boolean spaceInside = false;
            if (length == 0) {
                return null;
            }
            if (startsWith(trimedStr, '"') >= 0 || startsWith(trimedStr, '\'') >= 0) {
                return new Expression(trimedStr, false);
            }
            if (trimedStr.startsWith("new ")) {
                return new Expression(trimedStr, true);
            }
            for (int i = 0; i < length; i++) {
                if (trimedStr.charAt(i) == ' ') {
                    spaceInside = true;
                    break;
                }
            }
            return spaceInside ? new Declaration(trimedStr) : new Expression(trimedStr, false);
        }
        
        Expression(String expression, boolean isConstruction) {
            super(TYPE_STATEMENT_EXPRESSION);
            this.expression = expression;
            this.isNull = "null".equals(expression);
            this.isConstruction = isConstruction;
            this.expName = isConstruction ? expression.substring(4) : expression;
        }
        
        String getName() {
            return expName;
        }
        
        boolean isVariable() {
            char charactor = expName.charAt(0);
            if (Character.isDigit(charactor) || charactor == '-' || charactor == '-') {
                return false;
            } else if (charactor == '"' || charactor == '\'') {
                return false;
            } else {
                return true;
            }
        }
        
        @Override
        public String toString() {
            if (cppTranslationProcessed && isNull) {
                return "nullptr";
            } else {
                if (cppTranslationProcessed) {
                    String output;
                    if (!outterWarningSuppressed) {
                        JavaArgs args = resolveVariableIfNeeded();
                        if (args != null && args.isOutter) {
                            output = "/* outter-> */" + expression;
                        } else {
                            output = expression;
                        }
                    } else {
                        output = expression;
                    }
                    if (isAppendGetRefBySpVariable) {
                        return output + ".get()";
                    } else {
                        return output;
                    }
                } else {
                    return expression;
                }
            }
        }
        
        JavaArgs resolveVariableIfNeeded() {
            if (!variableResolved) {
                if (isVariable() && !isNull) {
                    resolvedVariable = parentParagraph.findVariableByName(getName(),
                            !resolveVariableLocally, wholeSearch);
                }
                variableResolved = true;
            }
            return resolvedVariable;
        }
        
        @Override
        int resolveCppValType() {
            if (isConstruction) {
                return CPP_TYPE_POINTER;
            }
            char charactor = expName.charAt(0);
            if (Character.isDigit(charactor) || charactor == '-' || charactor == '.') {
                return CPP_TYPE_ATOM;
            } else if (charactor == '"' || charactor == '\'') {
                return CPP_TYPE_STRING;
            }
//            if (!isVariable()) {
//                return CPP_TYPE_COMMON_EXP;
//            }
            if ("this".equals(getName())) {
                return CPP_TYPE_THIS;
            }
            JavaArgs args = resolveVariableIfNeeded();
            if (args == null) {
                return CPP_TYPE_NONE;
            }
            if (args.determinedCppType != CPP_TYPE_NONE) {
                return args.determinedCppType;
            }
            if (args.isArray) {
                return CPP_TYPE_ARRAY;
            }
            if (JavaField.isAtomType(args.type)) {
                return CPP_TYPE_ATOM;
            }
            if (JavaField.isDataStructureClass(args.type)) {
                return CPP_TYPE_DATA_SET;
            }
            if ("String".equals(args.type)) {
                return CPP_TYPE_STRING;
            }
            return CPP_TYPE_POINTER;
        }
        
        boolean isSpVariable() {
            if (isVariable()) {
                JavaArgs args = resolveVariableIfNeeded();
                if (args != null && args.isSp()) {
                    return true;
                }
            }
            return false;
        }
        
        void apeendGetBySpIfNecessary() {
            isAppendGetRefBySpVariable = isSpVariable();
        }
    }
    
    private static class Declaration extends Expression {
        
        JavaArgs args;
        
        Declaration(String expression) {
            super(expression, false);
            type = TYPE_STATEMENT_DECLARATION;
            args = JavaArgs.obtainJavaArgs(expression, false);
            resolveVariableLocally = true;
        }
        
        @Override
        void onCreate(CodeParagraph paragraph) {
            super.onCreate(paragraph);
            paragraph.addVariable(args);
        }
        
        @Override
        public String toString() {
            return args.toString();
        }
        
        @Override
        boolean isVariable() {
            return true;
        }
        
        @Override
        protected void onTranslationProcessing(UnseenClassHelper helper) {
            args.onTranslationProcessing();
            args.collectClassName(helper);
        }
        
        @Override
        String getName() {
            return args.name;
        }
        
        @Override
        JavaArgs resolveVariableIfNeeded() {
            return args;
        }
    }
    
    private static class Assign extends Operator {
        
        boolean assignSuppressed;
//        boolean assignWarning;
        String suffix;

        Assign() {
            super(TYPE_OP_ASSIGN);
        }
        
        @Override
        public String toString() {
            if (assignSuppressed) {
                return suffix != null ? prev.toString() + suffix : prev.toString();
            } else {
                return super.toString();
            }
        }
        
        static boolean isCppTypeWhichCannotAssign(int type) {
            switch (type) {
            case CPP_TYPE_ARRAY:
            case CPP_TYPE_DATA_SET:
            case CPP_TYPE_STRING:
                return true;
            }
            return false;
        }
        
        @Override
        protected void onTranslationProcessing(UnseenClassHelper helper) {
            if (prev != null && next != null) {
                final int prevCppType = prev.resolveCppValType();
                if (isCppTypeWhichCannotAssign(prevCppType)) {
                    if (prev.type == TYPE_STATEMENT_DECLARATION) {
                        if (next.type == TYPE_STATEMENT_METHOD) {
                            if (((Method) next).isConstruction) {
                                assignSuppressed = true;
                            }
                        } else if (next.type == TYPE_STATEMENT_ARRAY) {
                            Array array = (Array) next;
                            if (array.isConstruction) {
                                assignSuppressed = true;
                                StringBuffer buffer = new StringBuffer();
                                if (array.subStatements != null && array.subStatements.size() > 0) {
                                    CodeStatement statement = array.subStatements.get(0);
                                    if (statement != null) {
                                        buffer.append("(" + statement.toString() + ")");
                                    }
                                }
                                if (array.additionalBrackets != null && array.additionalBrackets.size() > 0) {
                                    for (int i = 0; i < array.additionalBrackets.size(); i++) {
                                        CodeStatement statement = array.additionalBrackets.get(i);
                                        if (statement != null) {
                                            buffer.append(statement.toString());
                                        }
                                    }
                                }
                                suffix = buffer.toString();
                            }
                        } else if (next.type == TYPE_STATEMENT_EXPRESSION) {
                            if (((Expression) next).isNull) {
                                assignSuppressed = true;
                            }
                        }
                    } else /*if (prev.type == TYPE_STATEMENT_EXPRESSION)*/ {
//                        assignWarning = true;
                        
                        if (next.type == TYPE_STATEMENT_METHOD) {
                            Method method = (Method) next;
                            if (method.isConstruction) {
                                if (prevCppType == CPP_TYPE_DATA_SET) {
                                    parentParagraph.addDummyStatement(this,
                                            new DummyStatement("// " + toString()));
                                    assignSuppressed = true;
                                    
                                    if (method.subStatements != null && method.subStatements.size() == 1) {
                                        parentParagraph.addDummyStatement(this,
                                                new DummyStatement(prev.toString() + ".clear()"));
                                        String consName = method.expression.getName();
                                        if (consName.contains("List")) {
                                            suffix = ".addAll(" + method.subStatements.get(0) + ")";
                                        } else if (consName.contains("Map") || consName.contains("Table")) {
                                            suffix = ".putAll(" + method.subStatements.get(0) + ")";
                                        } else {
                                            suffix = ".addAll(" + method.subStatements.get(0) + ") /* do check */";
                                        }
                                    } else {
                                        suffix = ".clear()";
                                    }
                                } else if (prevCppType == CPP_TYPE_STRING) {
                                    parentParagraph.addDummyStatement(this,
                                            new DummyStatement("// " + toString()));
                                    assignSuppressed = true;
                                    suffix = " = \"\"";
                                } else {
                                    // ignore
                                }
                            }
                        } else if (next.type == TYPE_STATEMENT_ARRAY) {
                            Array array = (Array) next;
                            if (array.isConstruction) {
                                StringBuffer buffer = new StringBuffer();
                                if (array.subStatements != null && array.subStatements.size() > 0) {
                                    CodeStatement statement = array.subStatements.get(0);
                                    if (statement != null) {
                                        buffer.append("(" + statement.toString() + ")");
                                    }
                                }
                                if (array.additionalBrackets != null && array.additionalBrackets.size() > 0) {
                                    for (int i = 0; i < array.additionalBrackets.size(); i++) {
                                        CodeStatement statement = array.additionalBrackets.get(i);
                                        if (statement != null) {
                                            buffer.append(statement.toString());
                                        }
                                    }
                                }
                                suffix = buffer.toString();
                                
                                String dummyCode = "/* auto generated */ Array<" + array.expression.getName() + "> tempArray" + suffix;
                                parentParagraph.addDummyStatement(this, new DummyStatement(dummyCode));
                                assignSuppressed = true;
                                suffix = " = tempArray";
                            }
                        } else if (next.type == TYPE_STATEMENT_EXPRESSION) {
                            if (((Expression) next).isNull) {
                                if (prevCppType == CPP_TYPE_DATA_SET || prevCppType == CPP_TYPE_ARRAY) {
                                    parentParagraph.addDummyStatement(this,
                                            new DummyStatement("// " + toString()));
                                    assignSuppressed = true;
                                    suffix = ".clear()";
                                } else if (prevCppType == CPP_TYPE_STRING) {
                                    parentParagraph.addDummyStatement(this,
                                            new DummyStatement("// " + toString()));
                                    assignSuppressed = true;
                                    suffix = " = \"\"";
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    private static class Bracket extends Operator {
        
        ArrayList<CodeStatement> subStatements;

        Bracket(int type) {
            super(type);
        }
        
        @Override
        void onCreate(CodeParagraph paragraph) {
            super.onCreate(paragraph);
            Operator pairedBracket = (Operator) next;
            CodeStatement firstStatmentInside = pairedBracket.prev;
            if (firstStatmentInside != null) {
                subStatements = new ArrayList<>();
                if (firstStatmentInside.type == TYPE_OP_COMMA) {
                    Operator currentComma = (Operator) firstStatmentInside;
                    int iterated = 0;
                    while (currentComma != null) {
                        iterated++;
                        CodeStatement commaBefore = currentComma.prev;
                        if (commaBefore != null) {
                            if (commaBefore.type == TYPE_OP_COMMA) {
                                throw new RuntimeException("Logic crash. commaBefore:" + commaBefore
                                        + " iterated:" + iterated);
                            }
                            subStatements.add(commaBefore);
                        }
                        CodeStatement commaAfter = currentComma.next;
                        currentComma = null;
                        if (commaAfter != null) {
                            if (commaAfter.type == TYPE_OP_COMMA) {
                                currentComma = (Operator) commaAfter;
                            } else {
                                subStatements.add(commaAfter);
                            }
                        }
                    }
                } else {
                    subStatements.add(firstStatmentInside);
                }
            }
        }
        
        int getBracketIndex() {
            return type;
        }
        
        @Override
        public String toString() {
//            String previousStr = prev != null ? prev.toString() : "";
            char leftBracket = sOpMap[getBracketIndex()].op[0];
            char rightBracket = sOpMap[getBracketIndex() + 1].op[0];
            if (subStatements == null || subStatements.size() == 0) {
                return /*previousStr +*/ leftBracket + "" + rightBracket;
            }
            StringBuffer buffer = new StringBuffer(/*previousStr*/);
            buffer.append(leftBracket);
            int length = subStatements.size();
            for (int i = 0; i < length; i++) {
                buffer.append(subStatements.get(i).toString());
                if (i < length - 1) {
                    buffer.append(", ");
                }
            }
            buffer.append(rightBracket);
            return buffer.toString();
        }
        
        @Override
        int resolveCppValType() {
            if (subStatements != null) {
                return subStatements.get(subStatements.size() - 1).resolveCppValType();
            }
            return super.resolveCppValType();
        }
    }
    
    private static class CastOp extends Bracket {
        
        CodeStatement castedTarget;
        boolean cppTraslationProcessed;

        CastOp(int type) {
            super(type);
        }
        
        @Override
        void onCreate(CodeParagraph paragraph) {
            super.onCreate(paragraph);
            Operator pairedBracket = (Operator) next;
            castedTarget = pairedBracket.next;
        }
        
        @Override
        public String toString() {
            if (cppTraslationProcessed) {
                CodeStatement first = subStatements != null ? subStatements.get(0) : null;
                String castedType = first != null ? first.toString() : "nullType";
                return "static_cast<" + castedType + ">(" + castedTarget.toString() + ")";
            } else {
                return super.toString() + castedTarget.toString();
            }
        }
        
        @Override
        protected void onTranslationProcessing(UnseenClassHelper helper) {
            cppTraslationProcessed = true;
        }
        
        @Override
        int resolveCppValType() {
            return castedTarget.resolveCppValType();
        }
    }
    
    private static class Method extends Bracket {
        
        Expression expression;
        boolean isConstruction;
        // used by throw keyword
        boolean newRemoved;
        boolean spAppendCheckSuppressed;

        Method(Expression expression) {
            super(TYPE_STATEMENT_METHOD);
            this.expression = expression;
            this.isConstruction = expression.isConstruction;
        }
        
        Method(Expression expression, int type) {
            super(type);
            this.expression = expression;
            this.isConstruction = expression.isConstruction;
        }
        
        @Override
        int getBracketIndex() {
            return TYPE_OP_LEFT_BRACKET;
        }
        
        @Override
        public String toString() {
            return expression + super.toString();
        }
        
        @Override
        int resolveCppValType() {
            if (isConstruction) {
                return CPP_TYPE_POINTER;
            }
            int argsCount = subStatements != null ? subStatements.size() : 0;
            return parentParagraph.resolveMethodReturnIsDataSet(expression.getName(),
                    argsCount);
        }
        
        void removeNew() {
            if (isConstruction && !newRemoved) {
                String exp = expression.expression;
                expression.expression = exp.substring(4);
                newRemoved = true;
            }
        }
        
        @Override
        protected void onTranslationProcessing(UnseenClassHelper helper) {
            if (subStatements != null && !spAppendCheckSuppressed) {
                for (int i = 0; i < subStatements.size(); i++) {
                    CodeStatement statement = subStatements.get(i);
                    if (statement != null &&
                            statement.type == TYPE_STATEMENT_EXPRESSION) {
                        ((Expression) statement).apeendGetBySpIfNecessary();
                    }
                }
            }
            if (expression.isConstruction) {
                String exp = expression.getName();
                int leftBranceIndex = exp.indexOf("<");
                if (leftBranceIndex >= 0) {
                    helper.addClass(exp.substring(0, leftBranceIndex));
                } else {
                    helper.addClass(exp);
                }
            }
        }
    }
    
    private static class Array extends Method {
        
        ArrayList<Bracket> additionalBrackets;

        Array(Expression expression) {
            super(expression, TYPE_STATEMENT_ARRAY);
        }
        
        @Override
        int getBracketIndex() {
            return TYPE_OP_LEFT_BRACKET_I;
        }
        
        @Override
        void onCreate(CodeParagraph paragraph) {
            super.onCreate(paragraph);
            CodeStatement currentBracket = ((Operator)next).next;
            while (currentBracket != null && (currentBracket.type == TYPE_OP_LEFT_BRACKET_I ||
                    currentBracket.type == TYPE_OP_LEFT_BRACKET_II)) {
                Bracket nextBracket = (Bracket) currentBracket;
                if (additionalBrackets == null) {
                    additionalBrackets = new ArrayList<>();
                }
                additionalBrackets.add(nextBracket);
                currentBracket = ((Operator)nextBracket.next).next;
            }
        }
        
        @Override
        public String toString() {
            String out = super.toString();
            if (additionalBrackets != null && additionalBrackets.size() > 0) {
                for (int i = 0; i < additionalBrackets.size(); i++) {
                    out += additionalBrackets.get(i).toString();
                }
            }
            return out;
        }
        
        @Override
        int resolveCppValType() {
            if (expression.isConstruction) {
                return CPP_TYPE_ARRAY;
            } else {
                JavaArgs args = expression.resolveVariableIfNeeded();
                if (args == null || !args.isArray) {
                    return CPP_TYPE_POINTER;
                }
                int suffixIndex = args.type.indexOf("[");
                if (suffixIndex < 0) {
                    return CPP_TYPE_POINTER;
                }
                String typeName = args.type.substring(0, suffixIndex);
                if (JavaField.isAtomType(typeName)) {
                    return CPP_TYPE_ATOM;
                }
                if (JavaField.isDataStructureClass(typeName)) {
                    return CPP_TYPE_DATA_SET;
                }
                if ("String".equals(typeName)) {
                    return CPP_TYPE_STRING;
                }
                return CPP_TYPE_POINTER;
            }
        }
    }
    
    private static class Invocation extends Operator {
        
        boolean leftValTypeResolved = false;
        int leftValType;
        
        CodeStatement subject;
        boolean subjectCppTypeResolved;
        int subjectCppType;

        Invocation() {
            super(TYPE_OP_INVOKE);
        }
        
        @Override
        void onCreate(CodeParagraph paragraph) {
            super.onCreate(paragraph);
            if (next != null && (next.type == TYPE_STATEMENT_METHOD ||
                    next.type == TYPE_STATEMENT_ARRAY ||
                    next.type == TYPE_STATEMENT_EXPRESSION)) {
                subject = prev;
                if (next.type == TYPE_STATEMENT_EXPRESSION) {
                    Expression exp = (Expression) next;
                    exp.outterWarningSuppressed = true;
                } else {
                    Method meee = (Method) next;
                    meee.expression.outterWarningSuppressed = true;
                }
//                Method method = (Method) next;
//                CodeStatement prevStatement = prev;
//                while (prevStatement != null && prevStatement.type == TYPE_OP_INVOKE) {
//                    Invocation prevInvocation = (Invocation) prevStatement;
//                    if (prevInvocation.next != null &&
//                            prevInvocation.next.type == TYPE_STATEMENT_METHOD) {
//                        prevStatement = prevInvocation.prev;
//                    } else {
//                        break;
//                    }
//                }
//                method.setSubjectStatement(prev);
            }
        }
        
        int resolveSubjectCppType() {
            if (!subjectCppTypeResolved && subject != null) {
                subjectCppType = subject.resolveCppValType();
                subjectCppTypeResolved = true;
            }
            return subjectCppType;
        }
        
        @Override
        boolean onPrintOp(StringBuffer buffer) {
            if (!leftValTypeResolved) {
                return false;
            }
            if (leftValType < CPP_TYPE_NONE) {
                buffer.append("->");
                return true;
            } else if (leftValType == CPP_TYPE_NONE) {
                buffer.append("::");
                return true;
            } else {
                return false;
            }
        }
        
        @Override
        int resolveCppValType() {
            if (subject != null) {
                if (isCurrentClass()) {
                    switch (next.type) {
                    case TYPE_STATEMENT_ARRAY:
                        Array array = (Array) next;
                        array.expression.wholeSearch = true;
                        int type = array.resolveCppValType();
                        array.expression.wholeSearch = false;
                        return type;
                    case TYPE_STATEMENT_METHOD:
                        Method method = (Method) next;
                        int argsCount = method.subStatements != null ? method.subStatements.size() : 0;
                        return parentParagraph.resolveMethodReturnIsDataSet(method.expression.getName(),
                                argsCount);
                    case TYPE_STATEMENT_EXPRESSION:
                        Expression exp = (Expression) next;
                        return parentParagraph.resolveFieldIsDataSet(exp.getName());
                    }
                } else {
                    return prev != null ? prev.resolveCppValType() : CodeStatement.CPP_TYPE_POINTER;
                }
            }
            return next != null ? next.resolveCppValType() : null;
        }
        
        boolean isCurrentClass() {
            if (subject == null) {
                return true;
            }
            int cppType = resolveSubjectCppType();
            if (cppType == CPP_TYPE_THIS) {
                return true;
            }
            if (subject.type == TYPE_STATEMENT_EXPRESSION) {
                Expression exp = (Expression) subject;
                if (exp.isVariable()) {
                    JavaArgs args = exp.resolveVariableIfNeeded();
                    if (args != null && args.type != null) {
                        if (parentParagraph.checkClsNameIsCurrentContext(args.type)) {
                            return true;
                        }
                    }
                }
            } /*else if (subject.type == TYPE_STATEMENT_METHOD) {
            }*/
            
            return false;
        }
        
        @Override
        protected void onTranslationProcessing(UnseenClassHelper helper) {
            if (!leftValTypeResolved) {
                leftValType = prev != null ? prev.resolveCppValType() : CPP_TYPE_REF;
                leftValTypeResolved = true;
                if (next != null && next.type == TYPE_STATEMENT_METHOD) {
                    Method method = (Method) next;
                    method.spAppendCheckSuppressed = leftValType == CPP_TYPE_DATA_SET;
                }
            }
        }
    }
    
    private static class Instanceof extends Operator {
        static final int INSTANCEOF_NONE = 0;
        static final int INSTANCEOF_OBJ_TYPE = 1;
        static final int INSTANCEOF_PTR_TYPE = 2;
        int instanceofReplacedOp = INSTANCEOF_NONE;

        Instanceof() {
            super(TYPE_OP_INSTANCEOF);
        }
        
        @Override
        public String toString() {
            if (instanceofReplacedOp > INSTANCEOF_NONE) {
                if (instanceofReplacedOp == INSTANCEOF_OBJ_TYPE) {
                    return "objIsType(" + prev + ", " + next + ")";
                } else {
                    boolean appendSpGet = false;
                    if (prev != null && prev.type == TYPE_STATEMENT_EXPRESSION) {
                        Expression exp = (Expression) prev;
                        if (exp.isSpVariable()) {
                            appendSpGet = true;
                        }
                    }
                    return "ptrIsType(" + prev + (appendSpGet ? ".get(), " : ", ") + next + ")";
                }
            }
            return super.toString();
        }
        
        @Override
        protected void onTranslationProcessing(UnseenClassHelper helper) {
            super.onTranslationProcessing(helper);
            if (prev != null) {
                int cppType = prev.resolveCppValType();
                if (cppType <= CodeStatement.CPP_TYPE_POINTER) {
                    instanceofReplacedOp = INSTANCEOF_PTR_TYPE;
                } else {
                    instanceofReplacedOp = INSTANCEOF_OBJ_TYPE;
                }
            }
        }
    }
    
    private static abstract class Keyword extends CodeStatement implements ICodeProcessor {
        
        ICodeProcessor previous;
        
        Keyword(int type, CodeParagraph parent) {
            super(type);
            this.parentParagraph = parent;
        }

        static Keyword obtainKeywordStatement(int type, CodeParagraph parent) {
            switch (type) {
            case TYPE_KEY_IF:
                return new If(parent);
            case TYPE_KEY_FOR:
                return new For(parent);
            case TYPE_KEY_DO_WHILE:
                return new Dowhile(parent);
            case TYPE_KEY_WHILE:
                return new While(parent);
            case TYPE_KEY_SWITCH:
                return new SwitchCase(parent);
            case TYPE_KEY_TRY_CATCH:
                return new TryCatch(parent);
            case TYPE_KEY_SYNC:
                return new Synchronized(parent);
            case TYPE_KEY_CASE:
            case TYPE_KEY_DEFAULT:
            case TYPE_KEY_RETURN:
            case TYPE_KEY_THROW:
            case TYPE_KEY_BREAK:
            case TYPE_KEY_CONTINUE:
                return new ControllerKey(type, parent);
            default:
                throw new RuntimeException("Invalid type:" + type);
            }
        }
        
        static class KeywordProcessHelper {
            static final int ERR_NOT_FOUND = 1;
            static final int ERR_NOT_COMPLETE = 2;
            static final int ERR_OKAY = 0;
            
            int[] tmp = new int[2];
            String code;
            int currentIndex;
            String statementCode;
            String prefix;
            
            static KeywordProcessHelper sPool;
            static int sPoolSize;
            
            KeywordProcessHelper next;
            
            private KeywordProcessHelper(String code) {
                setCode(code);
            }
            
            void setCode(String code) {
                this.code = code;
            }
            
            static KeywordProcessHelper obtain(String code) {
                if (sPool == null) {
                    return new KeywordProcessHelper(code);
                }
                KeywordProcessHelper out = sPool;
                out.setCode(code);
                sPool = out.next;
                sPoolSize--;
                return out;
            }
            
            void recycle() {
                code = null;
                statementCode = prefix = null;
                currentIndex = 0;
                
                if (sPoolSize < 5) {
                    next = sPool;
                    sPool = this;
                    sPoolSize++;
                }
            }
            
            boolean handleKeywordCommonLogic(int flag) {
                switch (flag) {
                case ERR_NOT_FOUND:
                    throw new RuntimeException("Logic crash. code:" + code);
                case ERR_NOT_COMPLETE:
                    return false;
                case ERR_OKAY:
                    return true;
                }
                throw new RuntimeException("Logic crash. code:" + code);
            }

            static boolean startsWithBracketStr(String code, int[] indexRange) {
                int index = startsWith(code, '(');
                if (index < 0) {
                    return false;
                }
                indexRange[0] = index + 1;
                boolean quotationMet = false;
                boolean singleQuoMet = false;
                boolean slashMet = false;
                
                int angle = 1;
                for (int i = index + 1; i < code.length(); i++) {
                    char charactor = code.charAt(i);
                    if (!singleQuoMet && !quotationMet && charactor == '(') {
                        angle++;
                    } else if (!singleQuoMet && !quotationMet && charactor == ')') {
                        angle--;
                    } else if (!slashMet && !singleQuoMet && charactor == '"') {
                        quotationMet = !quotationMet;
                    } else if (!slashMet && !quotationMet && charactor == '\'') {
                        singleQuoMet = !singleQuoMet;
                    } else if (quotationMet || singleQuoMet) {
                        slashMet = !slashMet && charactor == '\\';
                    }
                    if (angle == 0) {
                        indexRange[1] = i;
                        return true;
                    }
                }
                return false;
            }
            
            String extractStatementCode() {
                return statementCode != null && statementCode.length() > 0 ? statementCode : null;
            }
            
            String getAnchor() {
                String pre = prefix != null && prefix.length() > 0 ? prefix : null;
                if (pre != null && pre.endsWith(":")) {
                    return pre.substring(0, pre.length() - 1);
                } else {
                    return pre;
                }
            }
            
            int findNextKeyword(String keyword, boolean withStatement, boolean matchFirst) {
                statementCode = null;
                String codeLocal = code;
                if (currentIndex > 0) {
                    codeLocal = code.substring(currentIndex);
                }
                int[] result = Keyword.nextKeyWord(codeLocal, tmp, keyword);
                if (result[1] < 0) {
                    if (isEmptyOrSpace(codeLocal, 0, codeLocal.length())) {
                        return ERR_NOT_COMPLETE;
                    } else {
                        return ERR_NOT_FOUND;
                    }
                }
                if (result[1] > 0) {
                    String prefix = codeLocal.substring(0, result[1]).trim();
                    if (matchFirst && prefix.length() > 0) {
                        return ERR_NOT_FOUND;
                    }
                    this.prefix = prefix;
                }
                int startIndex = result[1] + keyword.length();
                if (!withStatement) {
                    currentIndex = startIndex;
                    return ERR_OKAY;
                }
                String tempCode = codeLocal.substring(startIndex);
                if (startsWithBracketStr(tempCode, tmp)) {
                    statementCode = tmp[1] > tmp[0] ? tempCode.substring(tmp[0], tmp[1]).trim() : null;
                    currentIndex += startIndex + tmp[1] + 1;
                    return ERR_OKAY;
                }
                return ERR_NOT_COMPLETE;
            }
            
            boolean findNextSigil(char sigil, boolean withStatement) {
                statementCode = null;
                String codeLocal = code;
//                if (currentIndex > 0) {
//                    codeLocal = code.substring(currentIndex);
//                }
                int index = indexOfSigil(codeLocal, sigil);
                if (index >= 0) {
                    if (withStatement) {
                        statementCode = codeLocal.substring(currentIndex, index).trim();
                    }
                    currentIndex = index + 1;
                    return true;
                }
                return false;
            }
            
            String extractSurplusCode() {
                return currentIndex < code.length() ? code.substring(currentIndex) : null;
            }
        }
        
        static int[] nextKeyWord(String code, int[] result) {
            return nextKeyWord(code, result, null);
        }
        
        static int[] nextKeyWord(String code, int[] result, String specified) {
            result[0] = result[1] = -1;
            final int length = code.length();
            boolean matchKeywordAttempted = true;
            boolean quotationMet = false;
            boolean slashMet = false;
            for (int i = 0; i < length; i++) {
                char character = code.charAt(i);
//                if (character == ' ' && !quotationMet) {
//                    matchKeywordAttempted = true;
//                    continue;
//                }
                if (Character.isLetter(character)) {
                    if (matchKeywordAttempted) {
                        if (specified != null && specified.length() > 0) {
                            if (specified.charAt(0) == character) {
                                boolean matched = true;
                                for (int k = 1; k < specified.length(); k++) {
                                    char nextCharacter = code.charAt(i + k);
                                    if (specified.charAt(k) != nextCharacter) {
                                        matched = false;
                                        break;
                                    }
                                }
                                if (matched) {
                                    if (i + specified.length() >= length ||
                                            !Character.isLetter(code.charAt(i + specified.length()))) {
                                        result[0] = -1;
                                        result[1] = i;
                                        return result;
                                    }
                                }
                            }
                        } else {
                            anchor:for (int j = 0; j < sKeyWord.length; j++) {
                                String keyword = sKeyWord[j];
//                                if (keyword.charAt(0) == character) {
//                                    
//                                }
                                if (i + keyword.length() - 1 < length) {
                                    for (int k = 0; k < keyword.length(); k++) {
                                        char nextCharacter = code.charAt(i + k);
                                        if (keyword.charAt(k) != nextCharacter) {
                                            continue anchor;
                                        }
                                    }
                                    if (i + keyword.length() >= length ||
                                            !Character.isLetter(code.charAt(i + keyword.length()))) {
                                        result[0] = j;
                                        result[1] = i;
                                        return result;
                                    }
                                }
                            }
                        }
                        matchKeywordAttempted = false;
                    }
                } else {
                    if (!slashMet && character == '"') {
                        quotationMet = !quotationMet;
                    } else if (quotationMet && character == '\\') {
                        slashMet = true;
                    } else {
                        slashMet = false;
                    }
                    matchKeywordAttempted = !quotationMet && character == ' ';
                }
            }
            
            return result;
        }
        
        @Override
        public String processCodeLine(ProcessorStack stack, String code) {
            KeywordProcessHelper helper = KeywordProcessHelper.obtain(code);
            try {
                if (onProcessGrammar(stack, helper)) {
                    onCreate(parentParagraph);
                    if (stack.pop() != this) {
                        throw new RuntimeException("Logic crash");
                    }
                }
                return helper.extractSurplusCode();
            } finally {
                helper.recycle();
            }
        }
        
        abstract boolean onProcessGrammar(ProcessorStack stack, KeywordProcessHelper helper);
        
        @Override
        public ICodeProcessor getPrev() {
            return previous;
        }

        @Override
        public void setPrev(ICodeProcessor processor) {
            previous = processor;
        }
        
        @Override
        void write(String prefix, PrintStream out) {
            out.println(prefix + this.toString());
        }
    }
    
    private static class If extends Keyword {
        static final String KEY_IF = "if";
        static final String KEY_ELSE = "else";
        
        private boolean ifResolved;
//        private int ifStatementResolveCount;
        CodeStatement ifStatement;
        CodeParagraph ifParagraph;
        
        ArrayList<CodeStatement> elseifStatmentList;
        ArrayList<CodeParagraph> elseifParagraphList;
        
        private boolean elseResolved;
        private boolean elseifResolving;
        CodeParagraph elseParagraph;
        
        If(CodeParagraph parent) {
            super(TYPE_KEY_IF, parent);
        }
        
        @Override
        public boolean onProcessGrammar(ProcessorStack stack, KeywordProcessHelper helper) {
            if (!ifResolved) {
                int flag = helper.findNextKeyword(KEY_IF, true, true);
                switch (flag) {
                case KeywordProcessHelper.ERR_NOT_FOUND:
                    throw new RuntimeException("Logic crash");
                case KeywordProcessHelper.ERR_NOT_COMPLETE:
//                    if (ifStatementResolveCount < 3) {
//                        ifStatementResolveCount++;
//                        return false;
//                    }
//                    throw new RuntimeException("Logic crash. code:" + helper.code);
                    return false;
                }
                String statementCode = helper.extractStatementCode();
                ifStatement = CodeParser.parseCode(parentParagraph, statementCode);
                ifParagraph = new CodeParagraph(parentParagraph);
                stack.push(ifParagraph);
                
                ifResolved = true;
                return false;
            }
            
            if (!elseResolved) {
                
                if (!elseifResolving) {
                    int elseFlag = helper.findNextKeyword(KEY_ELSE, false, true);
                    if (elseFlag == KeywordProcessHelper.ERR_NOT_FOUND) {
                        elseResolved = true;
                        return true;
                    } else if (elseFlag == KeywordProcessHelper.ERR_NOT_COMPLETE) {
                        return false;
                    }
                }
                
                int pairedIfFlag = helper.findNextKeyword(KEY_IF, true, true);
                if (pairedIfFlag == KeywordProcessHelper.ERR_NOT_FOUND) {
                    // this is a else grammar
                    elseParagraph = new CodeParagraph(parentParagraph);
                    stack.push(elseParagraph);
                    elseResolved = true;
                    elseifResolving = false;
                    return false;
                } else if (pairedIfFlag == KeywordProcessHelper.ERR_NOT_COMPLETE) {
                    elseifResolving = true;
                    return false;
                } else {
                    // this is a else if grammar
                    if (elseifStatmentList == null) {
                        elseifStatmentList = new ArrayList<>();
                    }
                    String statementCode = helper.extractStatementCode();
                    CodeStatement statement = CodeParser.parseCode(parentParagraph, statementCode);
                    if (statement != null) {
                        elseifStatmentList.add(statement);
                    }
                    if (elseifParagraphList == null) {
                        elseifParagraphList = new ArrayList<>();
                    }
                    CodeParagraph elseifParagraph = new CodeParagraph(parentParagraph);
                    elseifParagraphList.add(elseifParagraph);
                    stack.push(elseifParagraph);
                    elseifResolving = false;
                    return false;
                }
            }
            return true;
        }
        
        @Override
        void write(String prefix, PrintStream out) {
            out.println(prefix + "if (" + ifStatement.toString() + ") {");
            ifParagraph.write(prefix, out);
            if (elseifParagraphList != null) {
                for (int i = 0; i < elseifParagraphList.size(); i++) {
                    CodeStatement statement = elseifStatmentList.get(i);
                    CodeParagraph paragraph = elseifParagraphList.get(i);
                    out.println(prefix + "} else if (" + statement.toString() + ") {");
                    paragraph.write(prefix, out);
                }
            }
            if (elseParagraph != null) {
                out.println(prefix + "} else {");
                elseParagraph.write(prefix, out);
            }
            out.println(prefix + "}");
        }
        
        @Override
        public String toString() {
            if (ifStatement != null) {
                return "if (" + ifStatement.toString() + ")";
            } else {
                return "if ()";
            }
        }
        
        @Override
        void dispatchTranslation(UnseenClassHelper helper) {
            super.dispatchTranslation(helper);
            if (ifStatement != null) {
                ifStatement.dispatchTranslation(helper);
                ifParagraph.dispatchTranslation(helper);
            }
            if (elseifStatmentList != null) {
                for (int i = 0; i < elseifStatmentList.size(); i++) {
                    elseifStatmentList.get(i).dispatchTranslation(helper);
                    elseifParagraphList.get(i).dispatchTranslation(helper);
                }
            }
            if (elseParagraph != null) {
                elseParagraph.dispatchTranslation(helper);
            }
        }
    }
    
    private static class For extends Keyword {
        static final String KEY_FOR = "for";
        
        private boolean jobDone;
        private boolean autoCppIteratedMode;
        private String iteratedEleName;
        boolean isIterated;
        String anchor;
        
        CodeStatement[] commonStatmentList;
        Operator itratedOp;
        
        CodeParagraph paragraph;
        
        For(CodeParagraph parent) {
            super(TYPE_KEY_FOR, parent);
        }
        
        @Override
        boolean onProcessGrammar(ProcessorStack stack, KeywordProcessHelper helper) {
             if (jobDone) {
                 return true;
             }
             int forFlag = helper.findNextKeyword(KEY_FOR, true, false);
             if (!helper.handleKeywordCommonLogic(forFlag)) {
                 return false;
             }
             anchor = helper.getAnchor();
             
             paragraph = new CodeParagraph(parentParagraph);
             paragraph.setAnchor(anchor);
             
             String bracketStr = helper.extractStatementCode();
             if (bracketStr.contains(";")) {
                 if (commonStatmentList == null) {
                     commonStatmentList = new CodeStatement[3];
                 }
                 int firstSemiIndex = bracketStr.indexOf(";");
                 int lastSemiIndex = bracketStr.lastIndexOf(";");
                 String firstCodeStr = bracketStr.substring(0, firstSemiIndex);
                 commonStatmentList[0] = CodeParser.parseCode(paragraph, firstCodeStr);
                 String secondCodeStr = bracketStr.substring(firstSemiIndex + 1, lastSemiIndex);
                 commonStatmentList[1] = CodeParser.parseCode(paragraph, secondCodeStr);
                 String thirdCodeStr = bracketStr.substring(lastSemiIndex + 1);
                 commonStatmentList[2] = CodeParser.parseCode(paragraph, thirdCodeStr);
             } else {
                 isIterated = true;
                 itratedOp = (Operator) CodeParser.parseCode(paragraph, bracketStr);
             }
             
             stack.push(paragraph);
             jobDone = true;
             
             return false;
        }
        
        @Override
        void write(String prefix, PrintStream out) {
            out.println(prefix + toString() + " {");
            paragraph.write(prefix, out);
            out.println(prefix + "}");
        }
        
        @Override
        public String toString() {
            String anchorPrefix = anchor != null ? anchor + ":" : "";
            if (isIterated) {
                if (autoCppIteratedMode) {
                    return anchorPrefix + "for (auto& " + iteratedEleName + " : " + itratedOp.next.toString() + ")";
                } else {
                    return anchorPrefix + "for (" + itratedOp.toString() + ")";
                }
            } else {
                StringBuffer buffer = new StringBuffer(anchorPrefix + "for (");
                for (int i = 0; i < commonStatmentList.length; i++) {
                    CodeStatement statement = commonStatmentList[i];
                    if (statement != null) {
                        if (i > 0) {
                            buffer.append(" ");
                        }
                        buffer.append(statement.toString());
                    }
                    if (i < commonStatmentList.length - 1) {
                        buffer.append(";");
                    }
                }
                buffer.append(")");
                return buffer.toString();
            }
        }
        
        @Override
        void dispatchTranslation(UnseenClassHelper helper) {
            super.dispatchTranslation(helper);
            if (isIterated) {
                itratedOp.dispatchTranslation(helper);
            } else if (commonStatmentList != null) {
                for (int i = 0; i < commonStatmentList.length; i++) {
                    CodeStatement statement = commonStatmentList[i];
                    if (statement != null) {
                        statement.dispatchTranslation(helper);
                    }
                }
            }
            if (paragraph != null) {
                paragraph.dispatchTranslation(helper);
            }
        }
        
        @Override
        protected void onTranslationProcessing(UnseenClassHelper helper) {
            if (isIterated) {
                int cppType = itratedOp.next.resolveCppValType();
                if (cppType == CodeStatement.CPP_TYPE_DATA_SET) {
                    if (itratedOp.prev != null && itratedOp.prev.type == TYPE_STATEMENT_DECLARATION) {
                        autoCppIteratedMode = true;
                        Declaration declaration = (Declaration) itratedOp.prev;
                        iteratedEleName = declaration.getName();
//                        declaration.args.determinedCppType = CPP_TYPE_REF;
                    }
                }
            }
        }
    }
    
    private static class Dowhile extends Keyword {
        static final String KEY_DO = "do";
        static final String KEY_WHILE = "while";
        
        String anchor;
        
        private boolean doResolved;
        private boolean whileResolved;
        CodeParagraph paragraph;
        CodeStatement statement;
        
        Dowhile(CodeParagraph parent) {
            super(TYPE_KEY_DO_WHILE, parent);
        }

        @Override
        boolean onProcessGrammar(ProcessorStack stack, KeywordProcessHelper helper) {
            if (!doResolved) {
                if (helper.findNextKeyword(KEY_DO, false, false) != KeywordProcessHelper.ERR_OKAY) {
                    throw new RuntimeException("Logic crash");
                }
                anchor = helper.getAnchor();
                paragraph = new CodeParagraph(parentParagraph);
                paragraph.setAnchor(anchor);
                stack.push(paragraph);
                doResolved = true;
                return false;
            }
            if (!whileResolved) {
                int whileFlag = helper.findNextKeyword(KEY_WHILE, true, true);
                if (!helper.handleKeywordCommonLogic(whileFlag)) {
                    return false;
                }
                statement = CodeParser.parseCode(paragraph, helper.extractStatementCode());
                whileResolved = true;
            }
            // consume next ';'
            return helper.findNextSigil(';', false);
        }
        
        @Override
        void write(String prefix, PrintStream out) {
            String anchorPrefix = anchor != null ? anchor + ":" : "";
            out.println(prefix + anchorPrefix + "do {");
            paragraph.write(prefix, out);
            out.println(prefix + "} while (" + statement.toString() + ");");
        }
        
        @Override
        void dispatchTranslation(UnseenClassHelper helper) {
            super.dispatchTranslation(helper);
            if (paragraph != null) {
                paragraph.dispatchTranslation(helper);
            }
            if (statement != null) {
                statement.dispatchTranslation(helper);
            }
        }
    }
    
    private static class While extends Keyword {
        static final String KEY_WHILE = "while";
        
        String anchor;
        
        private boolean whileResolved;
        CodeParagraph paragraph;
        CodeStatement statement;
        
        While(CodeParagraph parent) {
            super(TYPE_KEY_WHILE, parent);
        }

        @Override
        boolean onProcessGrammar(ProcessorStack stack, KeywordProcessHelper helper) {
            if (!whileResolved) {
                int whileFlag = helper.findNextKeyword(KEY_WHILE, true, false);
                if (!helper.handleKeywordCommonLogic(whileFlag)) {
                    return false;
                }
                anchor = helper.getAnchor();
                
                paragraph = new CodeParagraph(parentParagraph);
                paragraph.setAnchor(anchor);
                
                statement = CodeParser.parseCode(paragraph, helper.extractStatementCode());
                stack.push(paragraph);
                
                whileResolved = true;
                return false;
            }
            return true;
        }
        
        @Override
        void write(String prefix, PrintStream out) {
            String anchorPrefix = anchor != null ? anchor + ":" : "";
            out.println(prefix + anchorPrefix + "while (" + statement.toString() + ") {");
            paragraph.write(prefix, out);
            out.println(prefix + "}");
        }
        
        @Override
        void dispatchTranslation(UnseenClassHelper helper) {
            super.dispatchTranslation(helper);
            if (statement != null) {
                statement.dispatchTranslation(helper);
            }
            if (paragraph != null) {
                paragraph.dispatchTranslation(helper);
            }
        }
    }
    
    private static class SwitchCase extends Keyword {
        
        static final String KEY_SWITCH = "switch";
        
        private boolean switchResolved;
        CodeParagraph paragraph;
        CodeStatement statement;

        SwitchCase(CodeParagraph parent) {
            super(TYPE_KEY_SWITCH, parent);
        }

        @Override
        boolean onProcessGrammar(ProcessorStack stack, KeywordProcessHelper helper) {
            if (!switchResolved) {
                int switchFlag = helper.findNextKeyword(KEY_SWITCH, true, true);
                if (!helper.handleKeywordCommonLogic(switchFlag)) {
                    return false;
                }
                statement = CodeParser.parseCode(parentParagraph, helper.extractStatementCode());
                paragraph = new SwitchCaseParagraph(parentParagraph);
                stack.push(paragraph);
                switchResolved = true;
                return false;
            }
            return true;
        }
        
        @Override
        void write(String prefix, PrintStream out) {
            out.println(prefix + "switch (" + statement.toString() + ") {");
            paragraph.write(prefix, out);
            out.println(prefix + "}");
        }
        
        @Override
        void dispatchTranslation(UnseenClassHelper helper) {
            super.dispatchTranslation(helper);
            if (statement != null) {
                statement.dispatchTranslation(helper);
            }
            if (paragraph != null) {
                paragraph.dispatchTranslation(helper);
            }
        }
    }
    
    private static class TryCatch extends Keyword {

        static final String KEY_TRY = "try";
        static final String KEY_CATCH = "catch";
        static final String KEY_FINALLY = "finally";
        
        private boolean tryResolved;
        CodeParagraph tryParagraph;
        
        private boolean catchResolved;
        ArrayList<CodeStatement> catchStatementList;
        ArrayList<CodeParagraph> catchParagraphList;
        
        private boolean finallyResolved;
        CodeParagraph finallyParagraph;
        
        TryCatch(CodeParagraph parent) {
            super(TYPE_KEY_TRY_CATCH, parent);
        }

        @Override
        boolean onProcessGrammar(ProcessorStack stack, KeywordProcessHelper helper) {
            if (!tryResolved) {
                int tryFlag = helper.findNextKeyword(KEY_TRY, false, true);
                if (tryFlag != KeywordProcessHelper.ERR_OKAY) {
                    throw new RuntimeException("Logic crash");
                }
                tryParagraph = new CodeParagraph(parentParagraph);
                stack.push(tryParagraph);
                tryResolved = true;
                return false;
            }
            
            if (!finallyResolved) {
                int catchFlag = helper.findNextKeyword(KEY_CATCH, true, true);
                if (catchFlag == KeywordProcessHelper.ERR_NOT_COMPLETE) {
                    return false;
                } else if (catchFlag == KeywordProcessHelper.ERR_OKAY) {
                    if (catchStatementList == null) {
                        catchStatementList = new ArrayList<>();
                    }
                    if (catchParagraphList == null) {
                        catchParagraphList = new ArrayList<>();
                    }
                    CodeParagraph paragraph = new CodeParagraph(parentParagraph);
                    catchStatementList.add(CodeParser.parseCode(paragraph,
                            helper.extractStatementCode()));
                    
                    catchParagraphList.add(paragraph);
                    stack.push(paragraph);
                    
                    catchResolved = true;
                    return false;
                } else {
                    int finallyFlag = helper.findNextKeyword(KEY_FINALLY, false, true);
                    if (finallyFlag == KeywordProcessHelper.ERR_NOT_COMPLETE) {
                        return false;
                    } else if (finallyFlag == KeywordProcessHelper.ERR_OKAY) {
                        finallyParagraph = new CodeParagraph(parentParagraph);
                        stack.push(finallyParagraph);
                        finallyResolved = true;
                        return false;
                    }
                }
            }
            
            if (!catchResolved && !finallyResolved) {
                throw new RuntimeException("Logic crash. Invalid java grammar");
            }
            return true;
        }
        
        @Override
        void write(String prefix, PrintStream out) {
            if (cppTranslationProcessed && finallyParagraph != null) {
                out.println(prefix + "defer {");
                finallyParagraph.write(prefix, out);
                out.println(prefix + "};");
                out.println();
            }
            out.println(prefix + "try {");
            tryParagraph.write(prefix, out);
            if (catchParagraphList != null) {
                for (int i = 0; i < catchParagraphList.size(); i++) {
                    CodeStatement statement = catchStatementList.get(i);
                    CodeParagraph paragraph = catchParagraphList.get(i);
                    out.println(prefix + "} catch (" + statement.toString() + ") {");
                    paragraph.write(prefix, out);
                }
            }
            if (!cppTranslationProcessed && finallyParagraph != null) {
                out.println(prefix + "} finally {");
                finallyParagraph.write(prefix, out);
            }
            out.println(prefix + "}");
        }
        
        @Override
        void dispatchTranslation(UnseenClassHelper helper) {
            super.dispatchTranslation(helper);
            if (tryParagraph != null) {
                tryParagraph.dispatchTranslation(helper);
            }
            if (catchParagraphList != null) {
                for (int i = 0; i < catchParagraphList.size(); i++) {
                    CodeStatement exceptionStatement = catchStatementList.get(i);
                    if (exceptionStatement != null &&
                            exceptionStatement.type == TYPE_STATEMENT_DECLARATION) {
                        Declaration dec = (Declaration) exceptionStatement;
                        dec.args.collectClassName(helper);
                    }
//                    catchStatementList.get(i).dispatchTranslation(helper);
                    catchParagraphList.get(i).dispatchTranslation(helper);
                }
            }
            if (finallyParagraph != null) {
                finallyParagraph.dispatchTranslation(helper);
            }
        }
    }
    
    private static class ControllerKey extends Keyword {
        
        boolean statementResolved;
        CodeStatement statement;
        boolean keywordResolved;
        String keyword;

        ControllerKey(int type, CodeParagraph paragraph) {
            super(type, paragraph);
            keyword = sKeyWord[type];
        }
        
        @Override
        boolean onProcessGrammar(ProcessorStack stack, KeywordProcessHelper helper) {
            if (!keywordResolved) {
                int keyFlag = helper.findNextKeyword(keyword, false, true);
                if (keyFlag != KeywordProcessHelper.ERR_OKAY) {
                    throw new RuntimeException("Logic crash");
                }
                keywordResolved = true;
            }
            if (!statementResolved) {
                boolean needResolveStatement = true;
                char sigil = ';';
                switch (this.type) {
                case TYPE_KEY_DEFAULT:
                    needResolveStatement = false;
                case TYPE_KEY_CASE:
                    sigil = ':';
                    break;
                case TYPE_KEY_RETURN:
                case TYPE_KEY_THROW:
                case TYPE_KEY_BREAK:
                case TYPE_KEY_CONTINUE:
                    break;
                }
                if (!helper.findNextSigil(sigil, needResolveStatement)) {
                    return false;
                }
                if (needResolveStatement) {
                    statement = CodeParser.parseCode(parentParagraph, helper.extractStatementCode());
                }
                statementResolved = true;
            }
            return true;
        }
        
        @Override
        void write(String prefix, PrintStream out) {
            out.println(prefix + this.toString());
        }
        
        @Override
        public String toString() {
            char sigil = type == TYPE_KEY_CASE || type == TYPE_KEY_DEFAULT ? ':' : ';';
            if (statement != null) {
                return keyword + " " + statement.toString() + sigil;
            } else {
                return keyword + sigil;
            }
        }
        
        @Override
        void dispatchTranslation(UnseenClassHelper helper) {
            super.dispatchTranslation(helper);
            if (statement != null) {
                statement.dispatchTranslation(helper);
            }
        }
        
        @Override
        void onCreate(CodeParagraph paragraph) {
            if (type == TYPE_KEY_RETURN && statement != null) {
                if (statement.type == TYPE_STATEMENT_EXPRESSION) {
                    Expression exp = (Expression) statement;
                    if (exp.isSpVariable()) {
                        paragraph.suggestReturnSpValue();
                    }
                }
            }
        }
        
        @Override
        protected void onTranslationProcessing(UnseenClassHelper helper) {
            if (type == TYPE_KEY_THROW && statement != null) {
                if (statement.type == TYPE_STATEMENT_METHOD) {
                    if (((Method) statement).isConstruction) {
                        ((Method) statement).removeNew();
                    }
                }
            } 
        }
    }
    
    private static class Synchronized extends Keyword {
        
        static final String KEY_SYNC = "synchronized";
        
        boolean resolved;
        CodeStatement statement;
        CodeParagraph paragraph;
        
        public Synchronized(CodeParagraph paragraph) {
            super(TYPE_KEY_SYNC, paragraph);
        }

        @Override
        boolean onProcessGrammar(ProcessorStack stack, KeywordProcessHelper helper) {
            if (!resolved) {
                int syncFlag = helper.findNextKeyword(KEY_SYNC, true, false);
                if (!helper.handleKeywordCommonLogic(syncFlag)) {
                    return false;
                }
                statement = CodeParser.parseCode(parentParagraph, helper.extractStatementCode());
                paragraph = new CodeParagraph(parentParagraph);
                stack.push(paragraph);
                resolved = true;
                return false;
            }
            return true;
        }
        
        @Override
        void write(String prefix, PrintStream out) {
            out.println(prefix + "synchronized (" + statement.toString() + ") {");
            paragraph.write(prefix, out);
            out.println(prefix + "}");
        }
        
        @Override
        void dispatchTranslation(UnseenClassHelper helper) {
            super.dispatchTranslation(helper);
            if (statement != null) {
                statement.dispatchTranslation(helper);
            }
            if (paragraph != null) {
                paragraph.dispatchTranslation(helper);
            }
        }
    }
    
    private interface ParagraphFinishedCallback {
        void onFinished(CodeParagraph paragraph);
    }
    
    private static class RootParagraph extends CodeParagraph {
        
        HashMap<String, JavaArgs> argsCache = new HashMap<>();
        JavaStatement context;
        CodeParagraph child;
        UnseenClassHelper helper;

        public RootParagraph(JavaStatement statement) {
            super(null);
            this.context = statement;
        }
        
        @Override
        void assignChild(CodeParagraph child) {
            child.tabSpace = "";
            this.child = child;
        }
        
        @Override
        JavaArgs findVariableByName(String name, boolean findGlobal, boolean wholeSearch) {
            JavaArgs args = argsCache.get(name);
            if (args != null) {
                return args;
            }
            boolean currentStatic = !wholeSearch && context.isStatic;
            JavaStatement current = context.parentStatement;
            JavaField result = null;
            anchor:while (current != null && result == null) {
                JavaParagraph javaParagraph = current.paragraph;
                if (javaParagraph == null || 
                        (javaParagraph.type != JavaParagraph.TYPE_ANONYMOUS_CLASS && 
                        javaParagraph.type != JavaParagraph.TYPE_CLASS)) {
                    currentStatic = !wholeSearch && current.isStatic;
                    current = current.parentStatement;
                    continue;
                }
                
                ClassParagraph paragraph = (ClassParagraph) javaParagraph;
                ArrayList<JavaField> staticFields = paragraph.staticFields;
                if (staticFields != null) {
                    for (int i = 0; i < staticFields.size(); i++) {
                        JavaField field = staticFields.get(i);
                        if (field.name.equals(name)) {
                            result = field;
                            break anchor;
                        }
                    }
                }
                ArrayList<JavaField> fields = paragraph.fields;
                if (fields != null && !currentStatic) {
                    for (int i = 0; i < fields.size(); i++) {
                        JavaField field = fields.get(i);
                        if (field.name.equals(name)) {
                            result = field;
                            break anchor;
                        }
                    }
                }
                currentStatic = !wholeSearch && current.isStatic;
                current = current.parentStatement;
            }
            
            if (result != null) {
                args = JavaArgs.obtainJavaArgs(result.feildType + " " + result.name, false);
                args.isStatic = result.isStatic;
                args.isGlobal = true;
                args.isArray = result.isArray;
                args.isOutter = result.parentStatement != null &&
                        result.parentStatement != context.parentStatement;
                argsCache.put(name, args);
            }
            return args;
        }

        @Override
        JavaStatement findJavaStatement() {
            return context;
        }
        
        @Override
        void suggestReturnSpValue() {
            if (context.type == JavaStatement.TYPE_METHOD) {
                JavaMethod method = (JavaMethod) context;
                method.suggestIsSp = true;
            }
        }
        
        static int resolveCppTypeByJavaType(String type) {
            if (type.endsWith("[]")) {
                return CodeStatement.CPP_TYPE_ARRAY;
            } else if (JavaField.isDataStructureClass(type)) {
                return CodeStatement.CPP_TYPE_DATA_SET;
            } else if ("String".equals(type)) {
                return CodeStatement.CPP_TYPE_STRING;
            }
            return CodeStatement.CPP_TYPE_POINTER;
        }
        
        @Override
        int resolveMethodReturnIsDataSet(String name, int argsCount) {
            JavaStatement current = context.parentStatement;
            boolean checkNoStatic = true/*!context.isStatic*/;
            while (current != null) {
                JavaParagraph javaParagraph = current.paragraph;
                if (javaParagraph == null || 
                        (javaParagraph.type != JavaParagraph.TYPE_ANONYMOUS_CLASS && 
                        javaParagraph.type != JavaParagraph.TYPE_CLASS)) {
//                    checkNoStatic = !current.isStatic;
                    current = current.parentStatement;
                    continue;
                }
                
                ClassParagraph paragraph = (ClassParagraph) javaParagraph;
                ArrayList<JavaMethod> staticJavaMethods = paragraph.staticMethods;
                int staticCount = staticJavaMethods != null ? staticJavaMethods.size() : 0;
                ArrayList<JavaMethod> javaMethods = checkNoStatic ? paragraph.methods : null;
                int count = javaMethods != null ? javaMethods.size() : 0;
                if (staticCount > 0 || count > 0) {
                    for (int i = 0; i < staticCount + count; i++) {
                        JavaMethod javaMethod = i < count ? javaMethods.get(i) : staticJavaMethods.get(i - count);
                        if (javaMethod.name.equals(name)) {
                            int methodArgCount = javaMethod.parameters != null ? javaMethod.parameters.size() : 0;
                            if (methodArgCount == argsCount) {
                                return resolveCppTypeByJavaType(javaMethod.returnType);
                            }
                        }
                    }
                }
                
//                checkNoStatic = !current.isStatic;
                current = current.parentStatement;
            }
            return CodeStatement.CPP_TYPE_NONE;
        }
        
        @Override
        int resolveFieldIsDataSet(String name) {
            JavaStatement current = context.parentStatement;
            boolean checkNoStatic = true/*!context.isStatic*/;
            while (current != null) {
                JavaParagraph javaParagraph = current.paragraph;
                if (javaParagraph == null || 
                        (javaParagraph.type != JavaParagraph.TYPE_ANONYMOUS_CLASS && 
                        javaParagraph.type != JavaParagraph.TYPE_CLASS)) {
//                    checkNoStatic = !current.isStatic;
                    current = current.parentStatement;
                    continue;
                }
                
                ClassParagraph paragraph = (ClassParagraph) javaParagraph;
                ArrayList<JavaField> staticJavaFields = paragraph.staticFields;
                int staticCount = staticJavaFields != null ? staticJavaFields.size() : 0;
                ArrayList<JavaField> javaFields = checkNoStatic ? paragraph.fields : null;
                int count = javaFields != null ? javaFields.size() : 0;
                if (staticCount > 0 || count > 0) {
                    for (int i = 0; i < staticCount + count; i++) {
                        JavaField javaField = i < count ? javaFields.get(i) : staticJavaFields.get(i - count);
                        if (javaField.name.equals(name)) {
                            return resolveCppTypeByJavaType(javaField.feildType);
                        }
                    }
                }
//                checkNoStatic = !current.isStatic;
                current = current.parentStatement;
            }
            return CodeStatement.CPP_TYPE_NONE;
        }
        
        @Override
        boolean checkClsNameIsCurrentContext(String currentClsName) {
            JavaStatement current = context.parentStatement;
            while (current != null) {
                if (current.name.equals(currentClsName)) {
                    return true;
                }
                current = current.parentStatement;
            }
            return false;
        }
        
        @Override
        public void write(String prefix, PrintStream out) {
            child.write(prefix, out);
        }
        
        @Override
        void dispatchTranslation(UnseenClassHelper helper) {
            child.dispatchTranslation(helper);
        }
        
        @Override
        void reportUnseenClass(String name) {
            helper.addClass(name);
        }
    }
    
    public static class CodeParagraph implements ICodeProcessor {
        ArrayList<JavaArgs> argsList;
        
        final ArrayList<CodeStatement> statements = new ArrayList<>();
        HashMap<Integer, ArrayList<DummyStatement>> dummyMap;
        String preservedCode;
        ICodeProcessor previous;
        boolean checkSelfBranceSuppressed;
        boolean branceConsumed;
        boolean brancePrintSuppressed;
        boolean autoPopAtNextRound;
        String anchor;
        ProcessHelper processHelper;
        ParagraphFinishedCallback callback;
        CodeParagraph parentParagraph;
        String tabSpace;
        int codeLine;
        
        public CodeParagraph(CodeParagraph parent) {
            assignParent(parent);
            brancePrintSuppressed = true;
        }
        
        void assignParent(CodeParagraph parent) {
            parentParagraph = parent;
            if (parent != null) {
                parent.assignChild(this);
            }
        }
        
        void assignChild(CodeParagraph child) {
//            if (tabSpace == null) {
                child.tabSpace = "    ";
//            } else {
//                child.tabSpace = tabSpace + "    ";
//            }
        }
        
        void setAnchor(String anchor) {
            this.anchor = anchor;
        }
        
        void addVariable(JavaArgs args) {
            if (argsList == null) {
                argsList = new ArrayList<>();
            }
            argsList.add(args);
        }
        
        void setIsBrancePrintSuppressed(boolean suppressed) {
            brancePrintSuppressed = suppressed;
        }
        
        JavaStatement findJavaStatement() {
            return parentParagraph != null ? parentParagraph.findJavaStatement() : null;
        }
        
        JavaArgs findVariableByName(String name, boolean findGlobal, boolean wholeSearch) {
            if (argsList != null) {
                for (int i = 0; i < argsList.size(); i++) {
                    JavaArgs localArgs = argsList.get(i);
                    if (name.equals(localArgs.name)) {
                        return localArgs;
                    }
                }
            }
            if (!findGlobal && parentParagraph != null &&
                    parentParagraph instanceof RootParagraph) {
                return null;
            }
            return parentParagraph != null ?
                    parentParagraph.findVariableByName(name, findGlobal, wholeSearch) : null;
        }
        
        JavaArgs findVariableByName(String name) {
            return findVariableByName(name, true, false);
        }
        
        int appendCodeLine() {
            return codeLine++;
        }
        
        static class ProcessHelper {
            String code;
            String preserveCode;
            
            int[] unHandledKey = new int[2];
            boolean keyIndexUnconsumed;
            int statementStartIndex;
            int statementEndIndex = -1;
            int consumedIndex = -1;
            boolean statementUnconsumed;
            String anchor;
            
            LineParser innerParser;
            
            static final int UNIT_NONE = -1;
            static final int UNIT_PARAGRAPH_START = 0;
            static final int UNIT_PARAGRAPH_END = 1;
            static final int UNIT_KEYWORD = 2;
            static final int UNIT_STATEMENT = 3;
            static final int UNIT_ANONYMOUS_PARAGRAPH_START = 4;
            
            ProcessHelper next;
            
            static ProcessHelper sPool;
            static int sPoolSize;
            
            static ProcessHelper obtain() {
                if (sPool == null) {
                    return new ProcessHelper();
                }
                ProcessHelper out = sPool;
                sPool = out.next;
                sPoolSize--;
                return out;
            }
            
            void recycle() {
                swapCode(null);
                
                if (sPoolSize < 5) {
                    next = sPool;
                    sPool = this;
                    sPoolSize++;
                }
            }
            
            void swapCode(String code) {
                this.code = code;
                preserveCode = null;
                unHandledKey[0] = unHandledKey[1] = -1;
                anchor = null;
                if (innerParser != null) {
                    innerParser.recycle();
                }
                innerParser = code != null ? LineParser.obtain(code) : null;
                statementStartIndex = 0;
                statementEndIndex = -1;
                consumedIndex = -1;
                keyIndexUnconsumed = statementUnconsumed = false;
            }
            
            String getPreserveCode() {
                return preserveCode;
            }
            
            String getAnchor() {
                return anchor;
            }
            
            int processBrance(int index) {
                for (int i = index - 1; i >= 0; i--) {
                    char charactor = code.charAt(i);
                    if (charactor == ' ') {
                        continue;
                    } else if (charactor == ':') {
                        return UNIT_PARAGRAPH_START;
                    } else if (charactor == ')') {
                        return UNIT_ANONYMOUS_PARAGRAPH_START;
                    } else {
                        return UNIT_NONE;
                    }
                }
                return UNIT_NONE;
            }
            
            int[] getKey() {
                return unHandledKey;
            }
            
            String extractStatementCode() {
                return code.substring(statementStartIndex, statementEndIndex);
            }
            
            int findNextProcessUnit() {
                if (keyIndexUnconsumed/* && unHandledKey[0] >= 0*/) {
                    keyIndexUnconsumed = false;
                    return UNIT_KEYWORD;
                }
                
                statementStartIndex = consumedIndex + 1;
                while (statementEndIndex < statementStartIndex) {
                    statementEndIndex = innerParser.nextSemicolon();
                    if (statementEndIndex == -1) {
                        break;
                    }
                }
                statementUnconsumed = statementEndIndex >= 0;
                int start = statementStartIndex;
                int end = statementUnconsumed ? statementEndIndex : code.length();
                if (start > end || start < 0 || end > code.length()) {
                    return UNIT_NONE;
                }
                String subStr = code.substring(start, end);
                int index = startsWith(subStr, '{');
                if (index >= 0) {
                    consumedIndex = start + index;
                    return UNIT_PARAGRAPH_START;
                }
                index = startsWith(subStr, '}');
                if (index >= 0) {
                    consumedIndex = start + index;
                    return UNIT_PARAGRAPH_END;
                }
                
                if (!keyIndexUnconsumed) {
                    Keyword.nextKeyWord(subStr, unHandledKey);
                    keyIndexUnconsumed = unHandledKey[0] >= 0;
                }
                
                do {
                    int leftBranceIndex = innerParser.nextLeftBrance();
                    if (leftBranceIndex >= 0 &&
                            (!keyIndexUnconsumed || leftBranceIndex < unHandledKey[1]) && 
                            (!statementUnconsumed || leftBranceIndex < statementEndIndex)) {
                        int unit = processBrance(leftBranceIndex);
                        if (unit == UNIT_PARAGRAPH_START) {
                            String prefix = code.substring(0, leftBranceIndex).trim();
                            if (prefix.endsWith(":")) {
                                anchor = prefix.substring(0, prefix.length() - 1);
                            }
                            consumedIndex = leftBranceIndex;
                        } else if (unit == UNIT_ANONYMOUS_PARAGRAPH_START) {
                            preserveCode = code.substring(statementStartIndex, leftBranceIndex);
                            consumedIndex = leftBranceIndex;
                        } else {
//                            innerParser.nextRightBrance();
                        }
                        return unit;
                    }
                    break;
                } while (true);
                
                if (keyIndexUnconsumed) {
                    if (statementEndIndex < 0 || unHandledKey[1] < statementEndIndex) {
                        keyIndexUnconsumed = false;
                        return UNIT_KEYWORD;
                    }
                }
                
                if (statementEndIndex >= 0) {
                    statementUnconsumed = false;
                    consumedIndex = statementEndIndex;
                    return UNIT_STATEMENT;
                }
                
                return UNIT_NONE;
            }
            
            String extractSurplusCode() {
                int startExtractIndex = consumedIndex + 1;
                if (startExtractIndex == 0) {
                    return code;
                }
                return startExtractIndex < code.length() ? code.substring(startExtractIndex) : null;
            }
        }
        
        @Override
        public String processCodeLine(ProcessorStack stack, String code) {
            if (isEmptyOrSpace(code, 0, code.length())) {
                return null;
            }
            
            if (processHelper == null) {
                processHelper = ProcessHelper.obtain();
            }
            if (preservedCode != null) {
                processHelper.swapCode(preservedCode + code);
                preservedCode = null;
            } else {
                processHelper.swapCode(code);
            }
            boolean done = false;
            try {
                done = autoPopAtNextRound || onProcess(stack, processHelper);
                return processHelper.extractSurplusCode();
            } finally {
                if (done) {
                    ICodeProcessor current = stack.pop();
                    if (current != this) {
                        throw new RuntimeException("Logic crash");
                    }
                    notifyParagraphFinished();
                    processHelper.recycle();
                    processHelper = null;
                }
            }
        }
        
        boolean onProcess(ProcessorStack stack, ProcessHelper helper) {
            int processFlag;
            boolean loopOn;
            do {
                processFlag = helper.findNextProcessUnit();
                loopOn = true;
                switch (processFlag) {
                case ProcessHelper.UNIT_PARAGRAPH_START:
                    if (!checkSelfBranceSuppressed && !branceConsumed) {
                        branceConsumed = true;
                    } else {
                        CodeParagraph paragraph = new CodeParagraph(this);
                        paragraph.brancePrintSuppressed = false;
                        paragraph.branceConsumed = true;
                        paragraph.setAnchor(helper.getAnchor());
                        stack.push(paragraph);
                        statements.add(new CodeParagraphWrapper(paragraph));
                        onParagraphProcessed(paragraph);
                        loopOn = false;
                    }
                    break;
                case ProcessHelper.UNIT_PARAGRAPH_END:
                    if (checkSelfBranceSuppressed) {
                        throw new RuntimeException("Logic crash");
                    }
                    if (!branceConsumed) {
                        throw new RuntimeException("Logic crash");
                    }
                    return true;
//                    break;
                case ProcessHelper.UNIT_KEYWORD:
                    int[] key = helper.getKey();
                    Keyword keyword = Keyword.obtainKeywordStatement(key[0], this);
                    if (keyword != null) {
                        stack.push(keyword);
                        statements.add(keyword);
                        onKeywordProcessed(keyword);
                        loopOn = false;
                        
                        if (!checkSelfBranceSuppressed && !branceConsumed) {
                            // this is a one line paragraph
                            autoPopAtNextRound = true;
                        }
                    } else {
                        throw new RuntimeException("Logic crash");
                    }
                    break;
                case ProcessHelper.UNIT_STATEMENT:
                    String codeLine = helper.extractStatementCode();
                    CodeStatement statement = CodeParser.parseCode(this, codeLine);
                    if (statement != null) {
                        statements.add(statement);
                        onCodeLineProcessed(statement);
                    }
                    
                    if (!checkSelfBranceSuppressed && !branceConsumed) {
                        // this is a one line paragraph
                        if (processFlag == ProcessHelper.UNIT_ANONYMOUS_PARAGRAPH_START) {
                            // TODO
                            throw new RuntimeException("Logic crash");
                        }
                        // move the cursor into next slot
                        helper.findNextProcessUnit();
                        return true;
                    }
                    break;
                case ProcessHelper.UNIT_ANONYMOUS_PARAGRAPH_START:
                    preservedCode = helper.getPreserveCode();
                    AnonymousParagraph paragraph = new AnonymousParagraph(this);
                    paragraph.setParagraphFinishedCallback(new ParagraphFinishedCallback() {
                        
                        @Override
                        public void onFinished(CodeParagraph paragraph) {
                            AnonymousParagraph anonyOne = (AnonymousParagraph) paragraph;
                            new JavaReader().installAnonymousClass(anonyOne.findJavaStatement(),
                                    null, guessAnonymousName(preservedCode), anonyOne, collectFinalLocalArgs());
                        }
                    });
                    stack.push(paragraph);
//                    statements.add(new CodeParagraphWrapper(paragraph));
                    onParagraphProcessed(paragraph);
                    loopOn = false;
                    break;
                case ProcessHelper.UNIT_NONE:
                default:
                    loopOn = false;
                }
            } while (loopOn);
            
            return false;
        }
        
        void addDummyStatement(CodeStatement who, DummyStatement dummy) {
            if (dummyMap == null) {
                dummyMap = new HashMap<>();
            }
            int index = who.index;
            ArrayList<DummyStatement> dummyStatements = dummyMap.get(index);
            if (dummyStatements == null) {
                dummyStatements = new ArrayList<>();
                dummyMap.put(index, dummyStatements);
            }
            dummyStatements.add(dummy);
        }
        
        private ArrayList<String> collectFinalLocalArgs() {
            ArrayList<String> result = null;
            CodeParagraph current = this;
            while (current != null && !(current instanceof RootParagraph)) {
                ArrayList<JavaArgs> localArgsList = current.argsList;
                if (localArgsList != null) {
                    for (int i = 0; i < localArgsList.size(); i++) {
                        JavaArgs args = localArgsList.get(i);
                        if (args.isFinal) {
                            if (result == null) {
                                result = new ArrayList<>();
                            }
                            result.add(args.getTypeStr());
                            result.add(args.name);
                        }
                    }
                }
                current = current.parentParagraph;
            }
            return result;
        }
        
        private String guessAnonymousName(String codeLine) {
            if (codeLine == null || codeLine.length() == 0) {
                return null;
            }
            int startIndex = codeLine.indexOf("new ");
            if (startIndex < 0) {
                return null;
            }
            int endIndex = startIndex + codeLine.substring(startIndex).indexOf("(");
            if (endIndex < 0) {
                return null;
            }
            return "Anonymous_" + codeLine.substring(startIndex + 4, endIndex);
        }
        
        protected void onParagraphProcessed(CodeParagraph paragraph) {
        }
        
        protected void onKeywordProcessed(Keyword keyword) {
        }
        
        protected void onCodeLineProcessed(CodeStatement statement) {
        }

        @Override
        public ICodeProcessor getPrev() {
            return previous;
        }

        @Override
        public void setPrev(ICodeProcessor processor) {
            previous = processor;
        }
        
        void setParagraphFinishedCallback(ParagraphFinishedCallback callback) {
            this.callback = callback;
        }
        
        protected void notifyParagraphFinished() {
            if (callback != null) {
                callback.onFinished(this);
            }
        }
        
        public void write(String prefix, PrintStream out) {
            if (!brancePrintSuppressed) {
                out.println(prefix + "{");
            }
            for (int i = 0; i < statements.size(); i++) {
                if (dummyMap != null) {
                    ArrayList<DummyStatement> dummyCodeLines = dummyMap.get(i);
                    if (dummyCodeLines != null) {
                        for (int j = 0; j < dummyCodeLines.size(); j++) {
                            writeStatement(dummyCodeLines.get(j), prefix, out);
                        }
                    }
                }
                writeStatement(statements.get(i), prefix, out);
            }
            if (!brancePrintSuppressed) {
                out.println(prefix + "}");
            }
        }
        
        protected void writeStatement(CodeStatement statement, String prefix, PrintStream out) {
            statement.write(prefix + tabSpace, out);
        }
        
        void reportUnseenClass(String name) {
            if (parentParagraph != null) {
                parentParagraph.reportUnseenClass(name);
            }
        }
        
        void dispatchTranslation(UnseenClassHelper helper) {
            for (int i = 0; i < statements.size(); i++) {
                statements.get(i).dispatchTranslation(helper);
            }
        }
        
        void suggestReturnSpValue() {
            if (parentParagraph != null) {
                parentParagraph.suggestReturnSpValue();
            }
        }
        
        int resolveMethodReturnIsDataSet(String methodName, int argsCount) {
            if (parentParagraph != null) {
                return parentParagraph.resolveMethodReturnIsDataSet(methodName, argsCount);
            }
            return CodeStatement.CPP_TYPE_NONE;
        }
        
        int resolveFieldIsDataSet(String fieldName) {
            if (parentParagraph != null) {
                return parentParagraph.resolveFieldIsDataSet(fieldName);
            }
            return CodeStatement.CPP_TYPE_NONE;
        }
        
        boolean checkClsNameIsCurrentContext(String currentClsName) {
            if (parentParagraph != null) {
                return parentParagraph.checkClsNameIsCurrentContext(currentClsName);
            }
            return false;
        }
    }
    
    private static class SwitchCaseParagraph extends CodeParagraph {

        public SwitchCaseParagraph(CodeParagraph parent) {
            super(parent);
        }
        
        @Override
        protected void writeStatement(CodeStatement statement, String prefix,
                PrintStream out) {
            if (statement instanceof Keyword && 
                    (statement.type == TYPE_KEY_CASE || statement.type == TYPE_KEY_DEFAULT)) {
                statement.write(prefix, out);
            } else {
                statement.write(prefix + tabSpace, out);
            }
        }
    }
    
    private static class AnonymousParagraph extends CodeParagraph implements CodeLineReader {
        
        int anonymousAnchor = 1;
        ArrayList<String> anonymousStrBuffer = new ArrayList<>();
        int currentScannedIndex;

        public AnonymousParagraph(CodeParagraph parent) {
            super(parent);
        }
        
        @Override
        public String processCodeLine(ProcessorStack stack, String code) {
            if (anonymousAnchor > 0) {
                /*
                 * Copy from JavaReader
                 */
                LineParser parser = LineParser.obtain(code);
                int leftBIndex = -1;
                int rightBIndex = -1;
                boolean findNexLeftBIndex = true;
                boolean findNextRightBIndex = true;
                do {
                    leftBIndex = findNexLeftBIndex ? parser.nextLeftBrance() : leftBIndex;
                    rightBIndex = findNextRightBIndex ? parser.nextRightBrance() : rightBIndex;
                    
                    if (leftBIndex < 0 && rightBIndex < 0) {
                        break;
                    }
                    
                    int minimumIndex = JavaReader.minimumIndex(leftBIndex, rightBIndex);
                    if (minimumIndex == leftBIndex) {
                        anonymousAnchor++;
                        findNexLeftBIndex = true;
                        findNextRightBIndex = false;
                    } else {
                        anonymousAnchor--;
                        findNextRightBIndex = true;
                        findNexLeftBIndex = false;
                    }
                } while (anonymousAnchor > 0);
                
                int endIndex = anonymousAnchor == 0 && rightBIndex >= 0 ? rightBIndex :
                    code.length();
                String anonymousCode;
                if (endIndex < code.length()) {
                    anonymousCode = code.substring(0, endIndex);
                } else {
                    anonymousCode = code;
                }
                anonymousStrBuffer.add(anonymousCode);
                if (anonymousAnchor == 0) {
                    stack.pop();
                    notifyParagraphFinished();
                    int startIndex = endIndex + 1;
                    return startIndex < code.length() ? code.substring(startIndex) : null;
                }
                return null;
            }
            
            throw new RuntimeException("Logic crash");
        }

        @Override
        public String nextLine(boolean trim, boolean collectCommentLine) {
            return nextLine();
        }

        @Override
        public String nextLine() {
            if (currentScannedIndex >= anonymousStrBuffer.size()) {
                return "#_#";
            }
            return anonymousStrBuffer.get(currentScannedIndex++);
        }

        @Override
        public ArrayList<String> collectPendingComment() {
            return null;
        }

        @Override
        public void clearComment() {
        }

        @Override
        public int getCurrentLineIndex() {
            return currentScannedIndex - 1;
        }
    }
}
