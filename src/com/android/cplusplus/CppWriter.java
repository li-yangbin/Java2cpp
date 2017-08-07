package com.android.cplusplus;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.android.cplusplus.JavaCodeReader.CodeParagraph;
import com.android.cplusplus.JavaReader.ClassParagraph;
import com.android.cplusplus.JavaReader.Clazz;
import com.android.cplusplus.JavaReader.CodeBlock;
import com.android.cplusplus.JavaReader.Enumeration;
import com.android.cplusplus.JavaReader.JavaCodeParagraph;
import com.android.cplusplus.JavaReader.JavaField;
import com.android.cplusplus.JavaReader.JavaFile;
import com.android.cplusplus.JavaReader.JavaMethod;
import com.android.cplusplus.JavaReader.JavaParagraph;
import com.android.cplusplus.JavaReader.JavaStatement;

public class CppWriter {
    
    private static final boolean CONSOLE_OUTPUT = Core.DEBUG_MODE || false;
    
    private static final CppParagraph sDummyParagraph = new CppParagraph() {
        @Override
        void addCppStatement(CppStatement statement, int order) {};
    };
    
    private PrintStream mOut;

    CppWriter() {
    }
    
    static CppFunction getAsInterface(Clazz javaClass, boolean bpMode, String scope) {
        CppFunction asInterface = new CppFunction();
        asInterface.name = "asInterface";
        asInterface.returnType = TypedValue.obtainCppTypedValue(javaClass.name, true);
        asInterface.parameters = new ArrayList<>();
        TypedValue binderArgs = TypedValue.obtainCppTypedValue("IBinder");
        binderArgs.isConst = true;
        binderArgs.type = VAL_CATE.REF;
        binderArgs.value = "obj";
        asInterface.parameters.add(binderArgs);
        if (bpMode) {
            asInterface.isStatic = true;
            asInterface.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
            return asInterface;
        }
        asInterface.scope = scope;
        CppParagraph paragraph = asInterface.createParagraphIfNeeded();
        paragraph.addCode("if (obj == nullptr) {");
        paragraph.addCode("    return nullptr;");
        paragraph.addCode("}");
        paragraph.addCode(asInterface.returnType.toString() +
                " iin = obj->queryLocalInterface(DESCRIPTOR);");
        paragraph.addCode("if (iin != nullptr && ptrIsType(iin, " + javaClass.name + ")) {");
        paragraph.addCode("    return static_cast<" + javaClass.name + "*>(iin);");
        paragraph.addCode("}");
        paragraph.addCode("return new Proxy(obj);");
        return asInterface;
    }
    
    static CppFunction getAsBinder(boolean bpMode, String scope) {
        CppFunction asBinder = new CppFunction();
        asBinder.name = "asBinder";
        asBinder.returnType = TypedValue.obtainCppTypedValue("IBinder", true);
        if (bpMode) {
            asBinder.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
            asBinder.isOverride = true;
            return asBinder;
        }
        asBinder.scope = scope;
        // return this;
        asBinder.createParagraphIfNeeded().addCode("return this;");
        return asBinder;
    }
    
    static CppFunction getOnTransact(Clazz javaClass, boolean bpMode, String scope) {
        CppFunction onTransact = new CppFunction();
        onTransact.name = "onTransact";
        onTransact.returnType = TypedValue.obtainCppTypedValue("boolean");
        onTransact.parameters = new ArrayList<>();
        TypedValue intArgs = TypedValue.obtainCppTypedValue("int");
        intArgs.value = "code";
        onTransact.parameters.add(intArgs);
        
        TypedValue dataArgs = new TypedValue("Parcel", VAL_CATE.REF);
        dataArgs.value = "_data";
        onTransact.parameters.add(dataArgs);
        
        TypedValue replyArgs = new TypedValue("Parcel", VAL_CATE.POINTER);
        replyArgs.value = "_reply";
        onTransact.parameters.add(replyArgs);
        
        TypedValue flagsArgs = TypedValue.obtainCppTypedValue("int");
        flagsArgs.value = "flags";
        onTransact.parameters.add(flagsArgs);
        if (bpMode) {
            onTransact.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
            onTransact.isOverride = true;
            return onTransact;
        }
        onTransact.scope = scope;
        return onTransact;
    }
    
    static CppFunction getProxyConstructor(boolean bpMode, String scope) {
        CppFunction constructor = new CppFunction();
        constructor.isConstructor = true;
        constructor.name = "Proxy";
        TypedValue remoteArgs = TypedValue.obtainCppTypedValue("IBinder");
        remoteArgs.isConst = true;
        remoteArgs.type = VAL_CATE.REF;
        remoteArgs.value = "remote";
        constructor.parameters = new ArrayList<>();
        constructor.parameters.add(remoteArgs);
        if (bpMode) {
            constructor.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
            return constructor;
        }
        constructor.scope = scope;
        constructor.createParagraphIfNeeded().addCode("mRemote = remote;");
        return constructor;
    }
    
    static CppFunction getProxyAsBinder(boolean bpMode, String scope) {
        CppFunction asBinder = new CppFunction();
        asBinder.name = "asBinder";
        asBinder.returnType = TypedValue.obtainCppTypedValue("IBinder", true);
        if (bpMode) {
            asBinder.isOverride = true;
            asBinder.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
            return asBinder;
        }
        asBinder.scope = scope;
        asBinder.createParagraphIfNeeded().addCode("return mRemote.get();");
        return asBinder;
    }
    
    static CppFunction getGetInterfaceDescriptor(boolean bpMode, String scope) {
        CppFunction getInterfaceDescriptor = new CppFunction();
        getInterfaceDescriptor.name = "getInterfaceDescriptor";
        getInterfaceDescriptor.returnType = TypedValue.obtainCppTypedValue("String");
        if (bpMode) {
            getInterfaceDescriptor.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
            return getInterfaceDescriptor;
        }
        getInterfaceDescriptor.scope = scope;
        getInterfaceDescriptor.createParagraphIfNeeded().addCode("return DESCRIPTOR;");
        return getInterfaceDescriptor;
    }
    
    CppFile processAidl2Cpp(JavaFile javaFile) {
        CppFile file = new CppFile();
        
        Clazz javaClazz = javaFile.primeClass;
        
        file.path = javaFile.path.replace(".aidl", ".cpp");
        file.name = javaClazz.name + ".cpp";
        
        processJavaImports(file, javaFile);
        CppParagraph topParagraph = addAndroidNamespace(file, javaFile);
        
        // Stub impl
        String scope = javaClazz.name + "::Stub::";
        CppFunction onTransaction_function = null;
        {
            CppField descriptor = new CppField();
            descriptor.isConst = true;
            descriptor.scope = scope;
            descriptor.value = TypedValue.obtainCppTypedValue("char");
            descriptor.value.value = descriptor.name = "DESCRIPTOR[]";
            descriptor.initedValue = "\"" + javaFile.packageName + "." + javaClazz.name + "\"";
            topParagraph.addCppStatement(descriptor);
            
            CppFunction constructor = new CppFunction();
            constructor.isConstructor = true;
            constructor.name = "Stub";
            constructor.scope = scope;
            constructor.createParagraphIfNeeded().addCode("attachInterface(this, DESCRIPTOR);");
            topParagraph.addCppStatement(constructor);
            
            topParagraph.addCppStatement(getAsInterface(javaClazz, false, scope));
            topParagraph.addCppStatement(getAsBinder(false, scope));
            onTransaction_function = getOnTransact(javaClazz, false, scope);
            topParagraph.addCppStatement(onTransaction_function);
            onTransaction_function.createParagraphIfNeeded();
        }
        
        scope = javaClazz.name + "::Stub::Proxy::";
        ArrayList<CppFunction> bnFunctions = new ArrayList<>();
        {
            topParagraph.addCppStatement(getProxyConstructor(false, scope));
            
            topParagraph.addCppStatement(getProxyAsBinder(false, scope));
            
            topParagraph.addCppStatement(getGetInterfaceDescriptor(false, scope));
            
            ClassParagraph javaParagraph = (ClassParagraph) javaClazz.paragraph;
            for (int i = 0; i < javaParagraph.methods.size(); i++) {
                CppFunction function = new CppFunction();
                JavaMethod javaMethod = javaParagraph.methods.get(i);
                function.name = javaMethod.name;
                function.scope = scope;
                function.returnType = TypedValue.obtainCppTypedValue(javaMethod.returnType);
                if (javaMethod.parameters != null) {
                    function.parameters = convertJavaPara2CppPara(javaMethod.parameters);
                }
                function.createParagraphIfNeeded();
                fillInAidlBpClassFunction(function, javaMethod);
                topParagraph.addCppStatement(function);
                bnFunctions.add(function);
            }
        }
        
        // finish onTransaction function
        {
            fillInAidlBnClassOnTransactFunction(onTransaction_function, bnFunctions);
        }
        return file;
    }
    
    private void fillInAidlBpClassFunction(CppFunction function, JavaMethod javaMethod) {
        ArrayList<String> snippet = null;
        if (function.paragraph.cppCode == null) {
            function.paragraph.cppCode = new ArrayList<>();
        }
        snippet = function.paragraph.cppCode;
        snippet.clear();
        
        boolean hasReply = function.hasReturnType();
        if (!hasReply && function.parameters != null) {
            for (int i = 0; i < function.parameters.size(); i++) {
                TypedValue parameter = function.parameters.get(i);
                if (parameter.isOut) {
                    hasReply = true;
                    break;
                }
            }
        }
        
        snippet.add("Parcel& _data = Parcel::obtain();");
        if (hasReply) {
            snippet.add("Parcel& _reply = Parcel::obtain();");
        }
        snippet.add("defer {");
        snippet.add("    _data.recycle();");
        if (hasReply) {
            snippet.add("    _reply.recycle();");
        }
        snippet.add("};");
        snippet.add("_data.writeInterfaceToken(DESCRIPTOR);");
        
        ArrayList<String> tempCode = new ArrayList<>();
        if (function.parameters != null) {
            for (int i = 0; i < function.parameters.size(); i++) {
                TypedValue parameter = function.parameters.get(i);
                if (!parameter.isOut) {
                    ParcelableHelper.obtainParcelInStatement(parameter, null, true, tempCode);
                    snippet.addAll(tempCode);
                }
            }
        }
        
        StringBuffer trasanctOp =
                new StringBuffer("mRemote->transact(Stub::TRANSACTION_" + function.name + ", _data, ");
        trasanctOp.append(hasReply ? "&_reply, " : "nullptr, ");
        if (javaMethod.isOneway) {
            trasanctOp.append("IBinder::FLAG_ONEWAY);");
        } else {
            trasanctOp.append("0);");
        }
        snippet.add(trasanctOp.toString());
        
        tempCode.clear();
        if (function.parameters != null) {
            for (int i = 0; i < function.parameters.size(); i++) {
                TypedValue parameter = function.parameters.get(i);
                if (parameter.isOut) {
                    ParcelableHelper.obtainParcelOutStatement(parameter, null, false, false, tempCode);
                    snippet.addAll(tempCode);
                }
            }
        }
        
        if (hasReply) {
            snippet.add("_reply.readException();");
        }
        
        // handle _reply
        if (!javaMethod.isOneway && function.hasReturnType()) {
            tempCode.clear();
            ParcelableHelper.obtainParcelOutStatement(function.returnType, "_res", false, true, tempCode);
            snippet.addAll(tempCode);
            snippet.add("return _res;");
        }
    }
    
    private void fillInAidlBnClassOnTransactFunction(CppFunction function,
            ArrayList<CppFunction> cppBpFunctions) {
        ArrayList<String> snippet = null;
        if (function.paragraph.cppCode == null) {
            function.paragraph.cppCode = new ArrayList<>();
        }
        snippet = function.paragraph.cppCode;
        snippet.clear();
        final String space4 = "    ";
        
        snippet.add("switch(code) {");
        
        snippet.add("case INTERFACE_TRANSACTION: {");
        snippet.add(space4 + "_reply->writeString(DESCRIPTOR);");
        snippet.add(space4 + "return true;");
        snippet.add("}");
        
        ArrayList<String> tempCode = new ArrayList<>();
        for (int i = 0; i < cppBpFunctions.size(); i++) {
            CppFunction bpFunction = cppBpFunctions.get(i);
            boolean haveParameters = bpFunction.parameters != null && bpFunction.parameters.size() > 0;
            
            snippet.add("case TRANSACTION_" + bpFunction.name + ": {");
            snippet.add(space4 + "_data.enforceInterface(DESCRIPTOR);");
            
            // extract values from parcel
            if (haveParameters) {
                for (int j = 0; j < bpFunction.parameters.size(); j++) {
                    TypedValue parameter = bpFunction.parameters.get(j);
                    if (!parameter.isOut) {
                        ParcelableHelper.obtainParcelOutStatement(parameter, null, true, true, tempCode);
                        for (String string : tempCode) {
                            snippet.add(space4 + string);
                        }
                    } else {
                        VAL_CATE old = parameter.type;
                        parameter.type = VAL_CATE.VAL;
                        snippet.add(space4 + parameter.toString() + ";");
                        parameter.type = old;
                    }
                }
            }
            
            // duty calls
            StringBuffer buffer = new StringBuffer(space4);
            if (bpFunction.hasReturnType()) {
                buffer.append(bpFunction.returnType.toString() + " _res = ");
            }
            buffer.append(bpFunction.name + "(");
            if (haveParameters) {
                for (int j = 0; j < bpFunction.parameters.size(); j++) {
                    TypedValue parameter = bpFunction.parameters.get(j);
                    buffer.append(parameter.value);
                    if (parameter.type == VAL_CATE.POINTER) {
                        buffer.append(".get()");
                    }
                    if (j < bpFunction.parameters.size() - 1) {
                        buffer.append(", ");
                    }
                }
            }
            buffer.append(");");
            snippet.add(buffer.toString());
            
            // handle out parameter
            boolean hasOutArgs = false;
            if (haveParameters) {
                for (int j = 0; j < bpFunction.parameters.size(); j++) {
                    TypedValue parameter = bpFunction.parameters.get(j);
                    if (parameter.isOut) {
                        hasOutArgs = true;
                        ParcelableHelper.obtainParcelInStatement(parameter, null, false, tempCode);
                        for (String string : tempCode) {
                            snippet.add(space4 + string);
                        }
                    }
                }
            }
            
            if (hasOutArgs || bpFunction.hasReturnType()) {
                snippet.add(space4 + "_reply->writeNoException();");
            }
            
            // write return value back
            if (bpFunction.hasReturnType()) {
                ParcelableHelper.obtainParcelInStatement(bpFunction.returnType, "_res", false, tempCode);
                for (String string : tempCode) {
                    snippet.add(space4 + string);
                }
            }
            
            // done
            snippet.add(space4 + "return true;");
            snippet.add("}");
        }
        
        snippet.add("}");
        snippet.add("return Binder::onTransact(code, _data, _reply, flags);");
    }
    
    private static CppParagraph addAndroidNamespace(BaseFile file, JavaFile javaFile) {
//        NameSpace androidSpace = new NameSpace();
//        androidSpace.name = "android";
//        CppParagraph topParagraph = androidSpace.createParagraphIfNeeded();
//        file.cppStatements.add(androidSpace);
        
        String packageName = javaFile.packageName;
        
        String[] sigilPath = packageName.split("\\.");
        int i = 0;
        if ("android".equals(sigilPath[0])) {
            i = 1;
        } /*else if ("java".equals(sigilPath[0])) {
            startIndex = 1;
        }*/ else if ("com".equals(sigilPath[0]) && "android".equals(sigilPath[1])) {
            sigilPath[0] = "android";
            sigilPath[1] = "com";
            i = 1;
        }
        NameSpace parentSpace = null;
        for (; i < sigilPath.length; i++) {
            NameSpace ns = new NameSpace();
            ns.name = sigilPath[i];
            if (parentSpace == null) {
                file.cppStatements.add(ns);
            } else {
                parentSpace.createParagraphIfNeeded().addCppStatement(ns);
            }
            parentSpace = ns;
        }
        
        return parentSpace.createParagraphIfNeeded();
    }
    
    ChFile processAidl2Ch(JavaFile javaFile) {
        ChFile file = new ChFile();
        
        Clazz javaClazz = javaFile.primeClass;
        
        file.path = javaFile.path.replace(".aidl", ".h");
        file.name = javaClazz.name + ".h";
        
        processJavaImports(file, javaFile);
        CppParagraph topParagraph = addAndroidNamespace(file, javaFile);
        
        // business interface declaration
        {
            CppClass cppClazz = new CppClass();
            cppClazz.name = javaClazz.name;
            cppClazz.extendedClasses = new ArrayList<>();
            cppClazz.extendedClasses.add("IInterface");
            cppClazz.createParagraphIfNeeded();
            
            CppClass stubClass = new CppClass();
            stubClass.name = "Stub";
            stubClass.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
            cppClazz.paragraph.addCppStatement(stubClass);
            
            // fill in business method(function)
            ClassParagraph javaPara = (ClassParagraph) javaClazz.paragraph;
            for (int i = 0; i < javaPara.methods.size(); i++) {
                JavaMethod javaMethod = javaPara.methods.get(i);
                CppFunction cppFunction = new CppFunction();
                cppFunction.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
                cppFunction.name = javaMethod.name;
                cppFunction.isVirtual = cppFunction.isPurelyVirtual = true;
                
                cppFunction.returnType = TypedValue.obtainCppTypedValue(javaMethod.returnType);
                
                if (javaMethod.parameters != null) {
                    ArrayList<TypedValue> cppParameters = convertJavaPara2CppPara(javaMethod.parameters);
                    cppFunction.parameters = cppParameters;
                }
                cppClazz.paragraph.addCppStatement(cppFunction);
            }
            topParagraph.addCppStatement(cppClazz);
        }
        
        // BnXXX class declaration
        {
            CppClass cppBnClazz = new CppClass();
            cppBnClazz.name = javaClazz.name + "::Stub";
            cppBnClazz.extendedClasses = new ArrayList<>();
            cppBnClazz.extendedClasses.add("Binder");
            cppBnClazz.extendedClasses.add(javaClazz.name);
            cppBnClazz.createParagraphIfNeeded();
            
            CppField field = new CppField();
            field.accessLevel = CppStatement.ACCESS_LEVEL_PRIVATE;
            field.value = TypedValue.obtainCppTypedValue("char");
            field.value.value = field.name = "DESCRIPTOR[]";
            field.isStatic = true;
            field.isConst = true;
            cppBnClazz.paragraph.addCppStatement(field);
            
            CppFunction constructor = new CppFunction();
            constructor.isConstructor = true;
            constructor.name = "Stub";
            constructor.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
            cppBnClazz.paragraph.addCppStatement(constructor);
            
            cppBnClazz.paragraph.addCppStatement(getAsInterface(javaClazz, true, null));
            
            cppBnClazz.paragraph.addCppStatement(getAsBinder(true, null));
            
            cppBnClazz.paragraph.addCppStatement(getOnTransact(javaClazz, true, null));
            
            CppClass proxyClass = new CppClass();
            proxyClass.accessLevel = CppStatement.ACCESS_LEVEL_PRIVATE;
            proxyClass.name = "Proxy";
            cppBnClazz.paragraph.addCppStatement(proxyClass);
            
            proxyClass = new CppClass();
            proxyClass.accessLevel = CppStatement.ACCESS_LEVEL_PRIVATE;
            proxyClass.name = "Proxy";
            proxyClass.isFriend = true;
            cppBnClazz.paragraph.addCppStatement(proxyClass);
            
            ClassParagraph javaPara = (ClassParagraph) javaClazz.paragraph;
            for (int i = 0; i < javaPara.methods.size(); i++) {
                JavaMethod javaMethod = javaPara.methods.get(i);
                CppField transaction_Field = new CppField();
                transaction_Field.accessLevel = CppStatement.ACCESS_LEVEL_PRIVATE;
                transaction_Field.value = TypedValue.obtainCppTypedValue("int");
                transaction_Field.value.value = transaction_Field.name =
                        "TRANSACTION_" + javaMethod.name;
                transaction_Field.isStatic = transaction_Field.isConst = true;
                transaction_Field.initedValue = "IBinder::FIRST_CALL_TRANSACTION + " + i;
                cppBnClazz.paragraph.addCppStatement(transaction_Field);
            }
            
            topParagraph.addCppStatement(cppBnClazz);
        }
        
        {
            CppClass cppBpClazz = new CppClass();
            cppBpClazz.name = javaClazz.name + "::Stub::Proxy";
            cppBpClazz.extendedClasses = new ArrayList<>();
            cppBpClazz.extendedClasses.add(javaClazz.name);
            cppBpClazz.createParagraphIfNeeded();
            
            CppField remoteField = new CppField();
            remoteField.accessLevel = CppStatement.ACCESS_LEVEL_PRIVATE;
            remoteField.value = TypedValue.obtainCppTypedValue("IBinder");
            remoteField.value.value = remoteField.name = "mRemote";
            cppBpClazz.paragraph.addCppStatement(remoteField);
            
            cppBpClazz.paragraph.addCppStatement(getProxyConstructor(true, null));
            
            cppBpClazz.paragraph.addCppStatement(getProxyAsBinder(true, null));
            
            cppBpClazz.paragraph.addCppStatement(getGetInterfaceDescriptor(true, null));
            
            ClassParagraph javaPara = (ClassParagraph) javaClazz.paragraph;
            for (int i = 0; i < javaPara.methods.size(); i++) {
                JavaMethod javaMethod = javaPara.methods.get(i);
                CppFunction transaction = new CppFunction();
                transaction.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
                transaction.name = javaMethod.name;
                transaction.returnType = TypedValue.obtainCppTypedValue(javaMethod.returnType);
                transaction.parameters = convertJavaPara2CppPara(javaMethod.parameters);
                transaction.isOverride = true;
                cppBpClazz.paragraph.addCppStatement(transaction);
            }
            
            topParagraph.addCppStatement(cppBpClazz);
        }
        
        return file;
    }
    
    static String getCppAtomTypeByJava(String atomType) {
        switch (atomType) {
        case JavaField.ATOM_TYPE_BYTE:
            return CppField.ATOM_TYPE_CHAR;
        case JavaField.ATOM_TYPE_SHORT:
            return CppField.ATOM_TYPE_SHORT;
        case JavaField.ATOM_TYPE_CHAR:
            return CppField.ATOM_TYPE_CHAR;
        case JavaField.ATOM_TYPE_DOUBLE:
            return CppField.ATOM_TYPE_DOUBLE;
        case JavaField.ATOM_TYPE_FLOAT:
            return CppField.ATOM_TYPE_FLOAT;
        case JavaField.ATOM_TYPE_INT:
            return CppField.ATOM_TYPE_INT;
        case JavaField.ATOM_TYPE_LONG:
            return CppField.ATOM_TYPE_INT64;
        case JavaField.ATOM_TYPE_BOOLEAN:
            return CppField.ATOM_TYPE_BOOL;
        }
        return "void";
    }
    
    private ArrayList<TypedValue> convertJavaPara2CppPara(ArrayList<String> javaParameters) {
        if (javaParameters == null) {
            return null;
        }
        ArrayList<TypedValue> result = null;
        for (String parameter : javaParameters) {
            String[] wordArray = parameter.split("\\s+");
            boolean isFinal = false;
            TypedValue cppParameter = null;
            boolean isIn = false;
            boolean isOut = false;
            for (String word : wordArray) {
                if (!isFinal && "final".equals(word)) {
                    isFinal = true;
                } else if (cppParameter == null && "in".equals(word)) {
                    isIn = true;
                } else if (cppParameter == null && "out".equals(word)) {
                    isOut = true;
                } else if (cppParameter == null) {
                    cppParameter = TypedValue.obtainCppTypedValue(word, true);
                    if (cppParameter.isSp || cppParameter.isString) {
                        cppParameter.type = VAL_CATE.REF;
                        cppParameter.isConst = true;
                    } else if (cppParameter.isDataContainer) {
                        cppParameter.type = VAL_CATE.REF;
                    }
                } else {
                    cppParameter.value = word;
                }
            }
            if (cppParameter != null) {
                cppParameter.isConst |= isFinal;
                cppParameter.isIn = isIn;
                cppParameter.isOut = isOut;
                
                if (result == null) {
                    result = new ArrayList<>();
                }
                result.add(cppParameter);
            }
        }
        return result;
    }
    
    private BaseFile[] processCommonJava(JavaFile javaFile) {
        ArrayList<BaseFile> out = new ArrayList<>();
        int pathEndIndex = javaFile.path.lastIndexOf(javaFile.primeClass.name + ".java");
        String path = pathEndIndex >= 0 ?
                javaFile.path.substring(0, pathEndIndex) : javaFile.path;
        
        processCommonJavaClass(path, javaFile, javaFile.primeClass, out);
        
        if (javaFile.otherClass != null) {
            for (int i = 0; i < javaFile.otherClass.size(); i++) {
                processCommonJavaClass(path, javaFile, javaFile.otherClass.get(i), out);
            }
        }
        
        return out.toArray(new BaseFile[out.size()]);
    }
    
    private void processJavaImports(BaseFile file, JavaFile javaFile) {
        if (javaFile.importStatements != null) {
            for (int i = 0; i < javaFile.importStatements.size(); i++) {
                String importStr = javaFile.importStatements.get(i);
                int startIndex = 0;
                String reversedPrefix = "";
                if (importStr.startsWith("android.")) {
                    startIndex = 8;
                } else if (importStr.startsWith("java.")) {
                    startIndex = 5;
                } else if (importStr.startsWith("com.android.")) {
                    startIndex = 12;
                    reversedPrefix = "com";
                }
                if (startIndex > 0) {
                    String afterPrefix = importStr.substring(startIndex);
                    int nextIndex = afterPrefix.indexOf('.');
                    if (afterPrefix.startsWith("annotation")) {
                        continue;
                    }
                    StringBuffer buffer = new StringBuffer(reversedPrefix);
                    StringBuffer includingBuffer = new StringBuffer();
                    String tag = null;
                    do {
                        if (afterPrefix.startsWith("*")) {
                            break;
                        }
                        nextIndex = afterPrefix.indexOf('.');
                        String prefix = nextIndex >= 0 ? afterPrefix.substring(0, nextIndex) : afterPrefix;
                        if (buffer.length() > 0) {
                            buffer.append("::" + prefix);
                        } else {
                            buffer.append(prefix);
                        }
                        if (includingBuffer.length() > 0) {
                            includingBuffer.append("/" + prefix);
                        } else {
                            includingBuffer.append(prefix);
                        }
                        if (nextIndex < 0) {
                            tag = prefix;
                            break;
                        }
                        afterPrefix = afterPrefix.substring(nextIndex + 1);
                    } while (true);
                    
                    if (includingBuffer.length() > 0) {
                        String fullName = includingBuffer.toString();
                        boolean isExisted;
                        if (Core.DEBUG_MODE || fullName.startsWith("net/wifi/") ||
                                fullName.startsWith("com/server/wifi/") ) {
                            isExisted = true;
                        } else {
                            isExisted = Core.isHeaderFileExisted(file.name, tag + ".h", fullName);
                        }
                        if (isExisted) {
                            file.includings.add("<" + fullName + ".h>");
                        } else {
                            file.includings.add("<" + fullName + ".h> // FIXME: this header is missing");
                        }
                        file.includingTags.add(tag);
                    }
                    
                    if (buffer.length() > 0) {
                        NameSpace nsUsing = new NameSpace();
                        nsUsing.using = true;
                        nsUsing.type = CppStatement.TYPE_NAMESPACE_USING;
                        nsUsing.name = buffer.toString();
                        
                        file.addCustomHeaderIfNecessary(nsUsing);
//                        file.cppStatements.add(nsUsing);
                    }
                }
            }
        }
        
        if (file instanceof CppFile) {
            String addedHFile = file.name.replace(".cpp", ".h");
            file.includingTags.add(addedHFile.substring(0, addedHFile.length() - 2));
            file.includings.add("\"" + addedHFile + "\"");
        }
    }
    
    static class UnseenClassHelper {
        BaseFile file;
        ArrayList<String> selfNames = new ArrayList<>();
        
        static final ArrayList<String> sJavaLangSet = new ArrayList<>();
        static {
            sJavaLangSet.add("Object");
            sJavaLangSet.add("String");
            sJavaLangSet.add("CharSequence");
            sJavaLangSet.add("Runnable");
            sJavaLangSet.add("Comparable");
            sJavaLangSet.add("Math");
            sJavaLangSet.add("Integer");
            sJavaLangSet.add("Float");
            sJavaLangSet.add("Charactor");
            sJavaLangSet.add("Boolean");
            sJavaLangSet.add("Long");
            sJavaLangSet.add("Short");
            sJavaLangSet.add("Double");
            sJavaLangSet.add("Throwable");
            sJavaLangSet.add("Array");
            sJavaLangSet.add("Enum");
            
            sJavaLangSet.add("Class");
            sJavaLangSet.add("ClassLoader");
            sJavaLangSet.add("Thread");
            
            sJavaLangSet.add("StringBuffer");
            sJavaLangSet.add("StringBuilder");
            
            sJavaLangSet.add("Exception");
            sJavaLangSet.add("Runtimexception");
            sJavaLangSet.add("NullPointerException");
            sJavaLangSet.add("SecurityException");
            sJavaLangSet.add("NumberFormatException");
            sJavaLangSet.add("ClassNotFoundException");
            sJavaLangSet.add("IllegalStateException");
            sJavaLangSet.add("IllegalArgumentException");
            sJavaLangSet.add("ClassNotFoundException");
            sJavaLangSet.add("UnsupportedOperationException");
        }
        
        static final ArrayList<String> sJavaIgnoredKey = new ArrayList<>();
        static {
            sJavaIgnoredKey.add("this");
            sJavaIgnoredKey.add("super");
            sJavaIgnoredKey.add("null");
            sJavaIgnoredKey.add("void");
            sJavaIgnoredKey.add("Void");
        }
        
        public UnseenClassHelper(BaseFile file, JavaFile javaFile) {
            this.file = file;
            if (javaFile.primeClass != null) {
                scanSelfNames(javaFile.primeClass);
            }
            if (javaFile.otherClass != null) {
                for (int i = 0; i < javaFile.otherClass.size(); i++) {
                    scanSelfNames(javaFile.otherClass.get(i));
                }
            }
        }
        
        private void scanSelfNames(Clazz outterClass) {
            if (outterClass == null) {
                return;
            }
            selfNames.add(outterClass.name);
            ClassParagraph javaParagraph = (ClassParagraph) outterClass.paragraph;
            if (javaParagraph.staticInnerClazzes != null) {
                for (int i = 0; i < javaParagraph.staticInnerClazzes.size(); i++) {
                    Clazz clazz = javaParagraph.staticInnerClazzes.get(i);
                    scanSelfNames(clazz);
                }
            }
            if (javaParagraph.innerClazzes != null) {
                for (int i = 0; i < javaParagraph.innerClazzes.size(); i++) {
                    Clazz clazz = javaParagraph.innerClazzes.get(i);
                    scanSelfNames(clazz);
                }
            }
            if (javaParagraph.enumerations != null) {
                for (int i = 0; i < javaParagraph.enumerations.size(); i++) {
                    Enumeration enumeration = javaParagraph.enumerations.get(i);
                    selfNames.add(enumeration.name);
                }
            }
        }
        
        boolean addClass(String name) {
            if (name == null || name.length() == 0) {
                return false;
            }
            if (JavaField.isAtomType(name, true)) {
                return false;
            }
            if (selfNames.contains(name)) {
                return false;
            }
            if (sJavaIgnoredKey.contains(name)) {
                return false;
            }
            ArrayList<String> imports = file.includingTags;
            for (int i = 0; i < imports.size(); i++) {
                if (name.equals(imports.get(i))) {
                    return false;
                }
            }
            imports.add(name);
            boolean isJavaLangClass = sJavaLangSet.contains(name);
            if (isJavaLangClass) {
                file.includings.add("<lang/" + name + ".h>");
                
                NameSpace nsUsing = new NameSpace();
                nsUsing.using = true;
                nsUsing.type = CppStatement.TYPE_NAMESPACE_USING;
                nsUsing.name = "lang::" + name;
                file.addCustomHeaderIfNecessary(nsUsing);
            } else {
                file.includings.add("\"" + name + ".h\"");
            }
            return true;
        }
    }
    
    private void processCommonJavaClass(String path, JavaFile javaFile, Clazz javaClass,
            ArrayList<BaseFile> out) {
        BaseFile hFile = new ChFile();
        out.add(hFile);
        
        hFile.name = javaClass.name + (".h");
        hFile.path = path + hFile.name;
        processJavaImports(hFile, javaFile);
        CppParagraph hTopParagraph = addAndroidNamespace(hFile, javaFile);
        
        CppParagraph cppTopParagraph;
        if (javaClass.isInterface) {
            cppTopParagraph = sDummyParagraph;
        } else {
            BaseFile cppFile = new CppFile();
            cppFile.name = javaClass.name + (".cpp");
            cppFile.path = path + cppFile.name;
            processJavaImports(cppFile, javaFile);
            cppTopParagraph = addAndroidNamespace(cppFile, javaFile);
            out.add(cppFile);
        }
        
        UnseenClassHelper helper = new UnseenClassHelper(hFile, javaFile);
        
        processCommonJavaClassInner(javaClass, hTopParagraph, cppTopParagraph,
                javaClass.name + "::", helper);
    }
    
    private void processCommonJavaClassInner(Clazz javaClass, CppParagraph hContainer,
            CppParagraph cppContainer, String scope, UnseenClassHelper importHelper) {
        CppClass cppClass = new CppClass();
        cppClass.apply(javaClass);
        if (javaClass.extendedClazz != null || javaClass.extendedInterface != null) {
            cppClass.extendedClasses = new ArrayList<>();
            if (javaClass.extendedClazz != null) {
                cppClass.extendedClasses.add(javaClass.extendedClazz);
            }
            if (javaClass.extendedInterface != null) {
                for (int i = 0; i < javaClass.extendedInterface.size(); i++) {
                    cppClass.extendedClasses.add(javaClass.extendedInterface.get(i));
                }
            }
        }
        CppClassParagraph hClassParagraph = cppClass.createParagraphIfNeeded();
        ClassParagraph javaParagraph = (ClassParagraph) javaClass.paragraph;
        if (javaClass.isInnerClass) {
            ArrayList<JavaMethod> methods = javaParagraph.methods;
            ArrayList<JavaMethod> consMethods = javaParagraph.constructionMethods;
            ArrayList<JavaMethod> staticMethods = javaParagraph.staticMethods;
            ArrayList<JavaField> staticFields = javaParagraph.staticFields;
            int methodCount = methods != null ? methods.size() : 0;
            int staticMethodCount = staticMethods != null ? staticMethods.size() : 0;
            int consMethodCount = consMethods != null ? consMethods.size() : 0;
            int methodTotalCount = methodCount + staticMethodCount + consMethodCount;
            int staticFieldCount = staticFields != null ? staticFields.size() : 0;
            boolean extendAnything = cppClass.extendedClasses != null &&
                    cppClass.extendedClasses.size() > 0;
            if (staticFieldCount == 0 && methodTotalCount == 0 && !extendAnything) {
                cppClass.type = CppStatement.TYPE_STRUCT;
            }
        }
        if (cppClass.type == CppStatement.TYPE_CLASS && !javaClass.isInterface &&
                (cppClass.extendedClasses == null || cppClass.extendedClasses.size() == 0)) {
            if (cppClass.extendedClasses == null) {
                cppClass.extendedClasses = new ArrayList<>();
            }
            cppClass.extendedClasses.add("Object");
            importHelper.addClass("Object");
        }
        hContainer.addCppStatement(cppClass);
        
        if (cppClass.type == CppStatement.TYPE_CLASS && hContainer != null &&
                hContainer.type == CppParagraph.TYPE_CLASS) {
            CppStatement parentStatement = hContainer.owner;
            while (parentStatement != null &&
                    parentStatement.type == CppStatement.TYPE_CLASS) {
                CppClass parentClass = (CppClass) parentStatement;
                CppClass friendClass = new CppClass();
                friendClass.isFriend = true;
                friendClass.name = parentClass.name;
                hClassParagraph.addCppStatement(friendClass);
                
                parentStatement = parentStatement.parentStatement;
            }
        }
        
        // static blocks
        {
            ArrayList<CodeBlock> javaStaticBlocks = javaParagraph.staticCodeBlocks;
            if (javaStaticBlocks != null && javaStaticBlocks.size() > 0) {
                CppFunction staticDummyFunc = new CppFunction();
                staticDummyFunc.generateDefaultComment();
                staticDummyFunc.order = javaStaticBlocks.get(0).line;
                staticDummyFunc.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
                staticDummyFunc.name = "static_init";
                staticDummyFunc.isStatic = true;
                staticDummyFunc.returnType = TypedValue.obtainCppTypedValue("void");
                hClassParagraph.addCppStatement(staticDummyFunc, 0);
                
                CppFunction staticDummyFuncImpl = new CppFunction(staticDummyFunc);
                staticDummyFuncImpl.isStatic = false;
                staticDummyFuncImpl.scope = scope;
                CppParagraph funcPara = staticDummyFuncImpl.createParagraphIfNeeded();
                for (int i = 0; i < javaStaticBlocks.size(); i++) {
                    JavaCodeParagraph codePara = (JavaCodeParagraph) javaStaticBlocks.get(i).paragraph;
                    copyJavaCodeParagraph2Cpp(codePara, funcPara, importHelper);
                }
                cppContainer.addCppStatement(staticDummyFuncImpl);
            }
        }
        
        // init blocks
        {
//            javaParagraph.codeBlocks;
        }
        
        // enum
        {
            ArrayList<Enumeration> javaEnumList = javaParagraph.enumerations;
            if (javaEnumList != null) {
                for (int i = 0; i < javaEnumList.size(); i++) {
                    Enumeration javaEnum = javaEnumList.get(i);
                    CppEnumeration cppEnum = new CppEnumeration();
                    cppEnum.apply(javaEnum);
                    CppParagraph enumPara = cppEnum.createParagraphIfNeeded();
                    copyJavaCodeParagraph2Cpp((JavaCodeParagraph) javaEnum.paragraph, enumPara, importHelper);
                    hClassParagraph.addCppStatement(cppEnum);
                }
            }
        }
        
        HashMap<String, String> pendingInitedValues = null;
        // fields
        {
            ArrayList<JavaField> staticField = javaParagraph.staticFields;
            ArrayList<JavaField> field = javaParagraph.fields;
            final int staticCount = staticField != null ? staticField.size() : 0;
            final int normalCount = field != null ? field.size() : 0;
            final int totalFieldCount = staticCount + normalCount;
            if (totalFieldCount > 0) {
                for (int i = 0; i < totalFieldCount; i++) {
                    JavaField javaField = i < staticCount ? staticField.get(i) : field.get(i - staticCount);
                    if (handleSpecialJavaField(javaField, hClassParagraph, cppContainer, scope, importHelper)) {
                        continue;
                    }
                    CppField cppField = new CppField();
                    cppField.apply(javaField);
                    cppField.isStatic = javaField.isStatic;
                    cppField.isConst = javaField.isStatic && javaField.isFinal;
                    String javaTypeAssumed;
                    if (javaField.feildType.equals("List")) {
                        javaTypeAssumed = "ArrayList";
                    } else if (javaField.feildType.startsWith("List<")) {
                        javaTypeAssumed = "ArrayList" + javaField.feildType.substring(4);
                    } else if (javaField.feildType.equals("Map")) {
                        javaTypeAssumed = "HashMap";
                    } else if (javaField.feildType.startsWith("Map<")) {
                        javaTypeAssumed = "HashMap" + javaField.feildType.substring(3);
                    } else {
                        javaTypeAssumed = javaField.feildType;
                    }
                    cppField.value = TypedValue.obtainCppTypedValue(javaTypeAssumed, false);
                    cppField.value.collectClassName(importHelper);
                    cppField.value.value = javaField.name;
                    final boolean withInitedValue = javaField.isStatic && javaField.isFinal &&
                            !javaField.isArray && JavaField.isAtomType(javaField.feildType);
                    cppField.initedValue = withInitedValue ? javaField.initedValue : null;
                    if (!withInitedValue && !javaField.isStatic && !cppField.value.isDataContainer) {
                        if (!javaField.isArray && JavaField.isAtomType(javaField.feildType)) {
                            if (pendingInitedValues == null) {
                                pendingInitedValues = new HashMap<>();
                            }
                            if (javaField.initedValue != null) {
                                pendingInitedValues.put(javaField.name, javaField.initedValue);
                            } else {
                                pendingInitedValues.put(javaField.name,
                                        CppField.getDefaultCppValue(cppField.value.name));
                            }
                        } else if (javaField.initedValue != null) {
                            if (pendingInitedValues == null) {
                                pendingInitedValues = new HashMap<>();
                            }
                            pendingInitedValues.put(javaField.name, javaField.initedValue);
                        }
                    }
                    cppField.isVolatile = javaField.isVolatile;
                    JavaParagraph javaFieldParagraph = javaField.paragraph;
                    if (javaFieldParagraph != null/* && withInitedValue*/ &&
                            javaFieldParagraph.type == JavaParagraph.TYPE_ARRAY) {
                        CppParagraph fieldPara = cppField.createParagraphIfNeeded();
                        JavaCodeParagraph javaCodeParagraph = (JavaCodeParagraph) javaFieldParagraph;
                        for (int j = 0; j < javaCodeParagraph.codeByOrder.size(); j++) {
                            fieldPara.addCode(javaCodeParagraph.codeByOrder.get(j));
                        }
                    }
                    if (!withInitedValue && javaField.isStatic /*&& 
                            (javaField.isArray || (!cppField.value.isDataContainer &&
                                    javaField.initedValue != null))*/) {
                        CppField cppFieldImp = new CppField();
                        cppFieldImp.order = cppField.order;
                        cppFieldImp.isConst = cppField.isConst;
                        cppFieldImp.value = new TypedValue(cppField.value);
                        cppFieldImp.name = cppField.name;
                        cppFieldImp.value.value = cppField.value.value;
                        cppFieldImp.scope = scope;
                        if (cppField.value.isDataContainer && javaField.initedValue != null &&
                                javaField.initedValue.length() > 0) {
                            cppFieldImp.initedValue = "/* " + javaField.initedValue + " */";
                        } else {
                            cppFieldImp.initedValue = javaField.initedValue;
                        }
                        cppFieldImp.paragraph = cppField.paragraph;
                        cppField.paragraph = null;
                        cppField.initedValue = null;
                        cppContainer.addCppStatement(cppFieldImp);
                    }
//                    if (javaField.isStatic && !javaField.isFinal) {
//                        hContainer.addCppStatement(cppField, 0);
//                    } else {
                        hClassParagraph.addCppStatement(cppField);
//                    }
                }
            }
        }
        
        // constructors
        {
            ArrayList<JavaMethod> javaConstructors = javaParagraph.constructionMethods;
            boolean constructorFound = false;
            if (javaConstructors != null) {
                for (int i = 0; i < javaConstructors.size(); i++) {
                    JavaMethod javaConstructor = javaConstructors.get(i);
                    CppFunction hConstructor = new CppFunction();
                    hConstructor.apply(javaConstructor);
                    hConstructor.isConstructor = true;
                    hConstructor.parameters = convertJavaPara2CppPara(javaConstructor.parameters);
                    if (hConstructor.parameters != null) {
                        for (int j = 0; j < hConstructor.parameters.size(); j++) {
                            TypedValue tv = hConstructor.parameters.get(j);
                            tv.collectClassName(importHelper);
                        }
                    }
                    hClassParagraph.addCppStatement(hConstructor);
                    
                    CppFunction cppConstructor = new CppFunction(hConstructor);
                    cppConstructor.dropCommentIfNotAutoGenerated();
                    cppConstructor.scope = scope;
                    if (true/*javaClass.extendedClazz != null*/) {
                        JavaCodeParagraph javaCodePara = (JavaCodeParagraph) javaConstructor.paragraph;
                        if (javaCodePara != null && javaCodePara.codeByOrder != null &&
                                javaCodePara.codeByOrder.size() > 0) {
                            String firstLineJavaCode = javaCodePara.codeByOrder.get(0).trim();
                            boolean startsWithThis = firstLineJavaCode.startsWith("this(");
                            boolean startsWithSuper = javaClass.extendedClazz != null && firstLineJavaCode.startsWith("super(");
                            if (startsWithThis || startsWithSuper) {
                                int indexSem;
                                int line = 1;
                                 do {
                                     indexSem = firstLineJavaCode.indexOf(");");
                                     if (indexSem >= 0) {
                                         break;
                                     }
                                     firstLineJavaCode += javaCodePara.codeByOrder.get(line++);
                                } while (line < 5);
                                if (indexSem > 0) {
                                    String middleStr = firstLineJavaCode.substring(startsWithThis ? 5 : 6, indexSem);
                                    String[] valuesArray = middleStr.split(",");
                                    for (int j = 0; j < valuesArray.length; j++) {
                                        valuesArray[j] = valuesArray[j].trim();
                                    }
                                    cppConstructor.addParentConstructor(startsWithSuper ?
                                            javaClass.extendedClazz : cppConstructor.name, valuesArray);
                                }
                            }
                        }
                    }
                    if (cppConstructor.parentNames == null || cppConstructor.parentNames.size() == 0) {
                        cppConstructor.initedMap = pendingInitedValues;
                    }
                    copyJavaCodeParagraph2Cpp((JavaCodeParagraph) javaConstructor.paragraph,
                            cppConstructor.createParagraphIfNeeded(), importHelper);
                    cppContainer.addCppStatement(cppConstructor);
                    constructorFound |= true;
                }
            }
            if (!constructorFound && cppClass.type == CppStatement.TYPE_CLASS) {
                CppFunction hConstructor = new CppFunction();
                hConstructor.generateDefaultComment();
                hConstructor.isConstructor = true;
                hConstructor.name = cppClass.name;
                hConstructor.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
                hClassParagraph.addCppStatement(hConstructor);
                
                CppFunction cppConstructor = new CppFunction(hConstructor);
                cppConstructor.scope = scope;
                cppConstructor.initedMap = pendingInitedValues;
                cppConstructor.createParagraphIfNeeded();
                cppContainer.addCppStatement(cppConstructor);
            }
            if (cppClass.type == CppStatement.TYPE_CLASS) {
                CppFunction hDestructor = new CppFunction();
                hDestructor.generateDefaultComment();
                hDestructor.isDestructor = true;
                hDestructor.name = cppClass.name;
                hDestructor.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
                hClassParagraph.addCppStatement(hDestructor);
                
                CppFunction cppDestructor = new CppFunction(hDestructor);
                cppDestructor.scope = scope;
                cppDestructor.createParagraphIfNeeded();
                cppContainer.addCppStatement(cppDestructor);
            }
        }
        
        // functions
        {
            ArrayList<JavaMethod> staticMethods = javaParagraph.staticMethods;
            ArrayList<JavaMethod> methods = javaParagraph.methods;
            final int staticCount = staticMethods != null ? staticMethods.size() : 0;
            final int normalCount = methods != null ? methods.size() : 0;
            final int totalMethodCount = staticCount + normalCount;
            if (totalMethodCount > 0) {
                for (int i = 0; i < totalMethodCount; i++) {
                    JavaMethod javaMethod = i < staticCount ? staticMethods.get(i) :
                        methods.get(i - staticCount);
                    CppFunction hFunction = new CppFunction();
                    hFunction.apply(javaMethod);
                    if (isNeedAddConstSuffix(javaMethod)) {
                        hFunction.isConst = true;
                    }
                    hFunction.isStatic = javaMethod.isStatic;
                    hFunction.isOverride = javaMethod.isOverride;
                    hFunction.isSynchronized = javaMethod.isSynchronized;
                    hFunction.isNative = javaMethod.isNative;
                    hFunction.returnType =
                            TypedValue.obtainCppTypedValue(javaMethod.returnType, !javaMethod.suggestIsSp);
                    hFunction.returnType.collectClassName(importHelper);
                    hFunction.parameters = convertJavaPara2CppPara(javaMethod.parameters);
                    if (hFunction.parameters != null) {
                        for (int j = 0; j < hFunction.parameters.size(); j++) {
                            TypedValue tv = hFunction.parameters.get(j);
                            tv.collectClassName(importHelper);
                        }
                    }
                    hFunction.isPurelyVirtual = javaClass.isInterface;
                    hFunction.isVirtual = javaClass.isInterface || javaMethod.isAbstract;
                    if (/*hFunction.isStatic || */hFunction.isInline) {
                        CppParagraph functionPara = hFunction.createParagraphIfNeeded();
                        copyJavaCodeParagraph2Cpp((JavaCodeParagraph) javaMethod.paragraph,
                                functionPara, importHelper);
                    }
                    hClassParagraph.addCppStatement(hFunction);
                    
                    if (/*!hFunction.isStatic && */!hFunction.isInline) {
                        CppFunction cppFunction = new CppFunction(hFunction);
                        cppFunction.dropCommentIfNotAutoGenerated();
                        cppFunction.isVirtual = cppFunction.isPurelyVirtual = false;
                        cppFunction.isOverride = false;
                        cppFunction.isStatic = false;
                        cppFunction.scope = scope;
                        copyJavaCodeParagraph2Cpp((JavaCodeParagraph) javaMethod.paragraph,
                                cppFunction.createParagraphIfNeeded(), importHelper);
                        cppContainer.addCppStatement(cppFunction);
                    }
                }
            }
        }
        
        // inner static class
        {
            ArrayList<Clazz> staticInnerClasses = javaParagraph.staticInnerClazzes;
            if (staticInnerClasses != null) {
                for (int i = 0; i < staticInnerClasses.size(); i++) {
                    Clazz clazz = staticInnerClasses.get(i);
                    if (clazz.isInterface) {
                        processCommonJavaClassInner(clazz, hClassParagraph, sDummyParagraph,
                                scope + clazz.name + "::", importHelper);
                    } else {
                        processCommonJavaClassInner(clazz, hClassParagraph, cppContainer,
                                scope + clazz.name + "::", importHelper);
                    }
                }
            }
        }
        
        // inner class
        {
            ArrayList<Clazz> innerClasses = javaParagraph.innerClazzes;
            if (innerClasses != null) {
                for (int i = 0; i < innerClasses.size(); i++) {
                    Clazz clazz = innerClasses.get(i);
                    installJavaInnerClass(clazz, javaClass.name);
                    processCommonJavaClassInner(clazz, hClassParagraph, cppContainer,
                            scope + clazz.name + "::", importHelper);
                }
            }
        }
    }
    
    private boolean isNeedAddConstSuffix(JavaMethod method) {
        String name = method.name;
        int argsCount = method.parameters != null ? method.parameters.size() : 0;
        if (name.equals("toString") && argsCount == 0) {
            return true;
        } else if (name.equals("hashCode") && argsCount == 0) {
            return true;
        } else if (name.equals("equals") && argsCount == 1) {
            return true;
        } else {
            return false;
        }
    }
    
    private boolean handleSpecialJavaField(JavaField javaField, CppParagraph hContainer,
            CppParagraph cppContainer, String scope, UnseenClassHelper helper) {
        if (javaField.isStatic && javaField.isFinal) {
            if ("CREATOR".equals(javaField.name)) {
                String fieldType = javaField.feildType;
                Matcher typeMM = Pattern.compile("<\\w+>").matcher(fieldType);
                if (typeMM.find()) {
                    String typeName = fieldType.substring(typeMM.start() + 1, typeMM.end() - 1);
                    
                    CppDefinition declare = new CppDefinition();
                    declare.generateDefaultComment();
                    declare.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
                    declare.name = "DECLARE_CREATOR";
                    declare.arguments.add(typeName);
                    hContainer.addCppStatement(declare);
                    
                    CppDefinition impl = new CppDefinition();
                    impl.generateDefaultComment();
                    impl.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
                    impl.name = "IMPLEMENT_CREATOR_VAR";
                    impl.arguments.add(typeName);
                    cppContainer.addCppStatement(impl);
                    
                    CppFunction implFunction = new CppFunction();
                    implFunction.generateDefaultComment();
                    implFunction.accessLevel = CppStatement.ACCESS_LEVEL_PUBLIC;
                    implFunction.name = "createFromParcel";
                    implFunction.scope = typeName + "::Creator::";
                    implFunction.returnType = TypedValue.obtainCppTypedValue(typeName, true);
                    implFunction.parameters = new ArrayList<>();
                    TypedValue args = new TypedValue("Parcel", VAL_CATE.REF);
                    args.value = "in";
                    implFunction.parameters.add(args);
                    
                    CppParagraph functionPara = implFunction.createParagraphIfNeeded();
                    functionPara.addCode(typeName + "* info = new " + typeName + "();");
                    functionPara.addCode("info->initFromParcel(in);");
                    functionPara.addCode("return info;");
                    if (javaField.paragraph != null &&
                            javaField.paragraph.type == JavaParagraph.TYPE_ANONYMOUS_CLASS) {
                        ClassParagraph classParagraph = (ClassParagraph) javaField.paragraph;
                        ArrayList<JavaMethod> methodList = classParagraph.methods;
                        if (methodList != null && methodList.size() > 0) {
                            for (int i = 0; i < methodList.size(); i++) {
                                JavaMethod method = methodList.get(i);
                                if ("createFromParcel".equals(method.name)) {
                                    CppCodeParagraph cppCodeParagraph = (CppCodeParagraph) functionPara;
                                    JavaCodeParagraph javaCodeParagraph = (JavaCodeParagraph) method.paragraph;
                                    cppCodeParagraph.codeParagraph =
                                            processJavaCodeParagraph2Cpp(javaCodeParagraph.innerCodeParagraph, helper);
                                    break;
                                }
                            }
                        }
                    }
                    cppContainer.addCppStatement(implFunction);
                    
                    return true;
                }
            }
        }
        if (javaField.paragraph != null &&
                javaField.paragraph.type == JavaParagraph.TYPE_ANONYMOUS_CLASS) {
            // pretend we have a java inner class
            Clazz innerClass = new Clazz();
            innerClass.isAutoGenerated = true;
            innerClass.line = javaField.line;
            innerClass.isInnerClass = true;
            innerClass.isStatic = javaField.isStatic;
            innerClass.accessLevel = javaField.accessLevel;
            innerClass.name = javaField.feildType + "_" + javaField.name;
            innerClass.extendedClazz = javaField.feildType;
            innerClass.paragraph = javaField.paragraph;
            if (!javaField.isStatic) {
                CppClass outterClass = (CppClass) hContainer.owner;
                String outterClassName = outterClass.name;
                
                installJavaInnerClass(innerClass, outterClassName);
                javaField.initedValue = "new " + innerClass.name + "(this)";
            } else {
                javaField.initedValue = "new " + innerClass.name + "()";
            }
            processCommonJavaClassInner(innerClass, hContainer, cppContainer,
                    scope + innerClass.name + "::", helper);
        }
        return false;
    }
    
    static void installJavaInnerClass(Clazz innerClass, String outterClassName) {
        if (innerClass.isStatic) {
            return;
        }
        ClassParagraph paragraph = (ClassParagraph) innerClass.paragraph;
        if (paragraph.constructionMethods == null || paragraph.constructionMethods.size() == 0) {
            paragraph.constructionMethods = new ArrayList<>();
            
            JavaMethod consMethod = new JavaMethod();
            consMethod.line = Integer.MAX_VALUE;
            consMethod.isAutoGenerated = true;
            consMethod.accessLevel = JavaField.LEVEL_PUBLIC;
            consMethod.isConstruction = true;
            consMethod.name = innerClass.name;
            consMethod.parentStatement = innerClass;
            consMethod.paragraph = new JavaCodeParagraph();
            consMethod.parameters = new ArrayList<>();
            consMethod.parameters.add(outterClassName + " outterInstance");
            paragraph.constructionMethods.add(consMethod);
        } else {
            JavaMethod firstOne = paragraph.constructionMethods.get(0);
            if (firstOne.parameters == null) {
                firstOne.parameters = new ArrayList<>();
            }
            firstOne.parameters.add(0, outterClassName + " outterInstance");
        }
        
        if (paragraph.fields == null) {
            paragraph.fields = new ArrayList<>();
        }
        JavaField outterField = new JavaField();
        outterField.line = Integer.MAX_VALUE;
        outterField.isAutoGenerated = true;
        outterField.parentStatement = innerClass;
        outterField.feildType = outterClassName;
        outterField.name = "outter";
        outterField.initedValue = "outterInstance";
        paragraph.fields.add(outterField);
    }

    private void copyJavaCodeParagraph2Cpp(JavaCodeParagraph from, CppParagraph to,
            UnseenClassHelper helper) {
        if (from == null) {
            return;
        }
        if (!(from instanceof JavaCodeParagraph)) {
            return;
        }
        JavaCodeParagraph codeFrom = (JavaCodeParagraph) from;
        if (codeFrom.codeByOrder == null || codeFrom.codeByOrder.size() == 0) {
            return;
        }
        to.addCode("// TODO: java code");
        ArrayList<String> javaCode = codeFrom.codeByOrder;
        for (int i = 0; i < javaCode.size(); i++) {
            to.addCode(processJavaCode2CppCode(javaCode.get(i)));
        }
        if (to instanceof CppCodeParagraph) {
            ((CppCodeParagraph) to).codeParagraph =
                    processJavaCodeParagraph2Cpp(codeFrom.innerCodeParagraph, helper);
        }
    }
    
//    private static final String[] sJavaTarget = {"null"};
//    private static final String[] sCppRplacement = {"nullptr"};
    
    private String processJavaCode2CppCode(String javaCode) {
//        if (javaCode == null || javaCode.length() == 0) {
//            return javaCode;
//        }
//        for (int i = 0; i < sJavaTarget.length; i++) {
//            if (javaCode.contains(sJavaTarget[i])) {
//                javaCode = javaCode.replace(sJavaTarget[i], sCppRplacement[i]);
//            }
//        }
        return javaCode;
    }
    
    private CodeParagraph processJavaCodeParagraph2Cpp(CodeParagraph paragraph, UnseenClassHelper helper) {
        if (paragraph != null) {
            paragraph.dispatchTranslation(helper);
        }
        return paragraph;
    }
    
    public int write(JavaFile javaFile) {
        return write(javaFile, null);
    }
    
    public int write(JavaFile javaFile, String outPath) {
        if (javaFile == null) {
            return 0;
        }
        if (javaFile.isAidl) {
            if (javaFile.primeClass != null) {
                writeCommonFile(processAidl2Ch(javaFile), outPath);
                writeCommonFile(processAidl2Cpp(javaFile), outPath);
                return 2;
            }
            return 0;
        } else {
            BaseFile[] outFiles = processCommonJava(javaFile);
            int sum = 0;
            for (int i = 0; i < outFiles.length; i++) {
                if (outFiles[i] == null) {
                    continue;
                }
                writeCommonFile(outFiles[i], outPath);
                sum++;
            }
            return sum;
        }
    }

    private void writeCommonFile(BaseFile baseFile, String outPath) {
        ArrayList<CppStatement> statementList = baseFile.cppStatements;
        if (statementList == null || statementList.size() == 0) {
            return;
        }
        
        try {
            if (!CONSOLE_OUTPUT) {
                if (outPath != null) {
                    System.out.println("...output file:" + baseFile.name);
                    File outFile = new File(outPath + "/" + baseFile.name);
//                    File outFile = new File("/work/wifi2c++/test/" + baseFile.name);
                    if (outFile.exists()) {
                        outFile.delete();
                    }
                    try {
                        if (!outFile.createNewFile()) {
                            throw new RuntimeException("Can not create file:" + outFile.getAbsolutePath());
                        }
                    } catch (IOException e) {
                    }
                    
                    mOut = new PrintStream(new FileOutputStream(outFile), true);
                }
            }
            
            if (baseFile instanceof ChFile) {
                printf("#pragma once");
                printf();
            }
            if (baseFile.includings.size() > 0) {
                for (int i = 0; i < baseFile.includings.size(); i++) {
                    printf("#include " + baseFile.includings.get(i));
                }
                printf();
            }
            writeStatementList("", statementList, null);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } finally {
            if (mOut != null) {
                mOut.flush();
                mOut.close();
                mOut = null;
            }
        }
    }
    
    private void writeStatementList(String prefix, ArrayList<CppStatement> statementList,
            CppStatement parent) {
        CppStatement lastStatement = null;
        int currentAccessLevel = CppStatement.ACCESS_LEVEL_DEFAULT;
        for (CppStatement statement : statementList) {
            if (lastStatement != null && lastStatement.type != statement.type) {
                printf();
            } else if (statement.type == CppStatement.TYPE_CLASS && statement.paragraph != null) {
                printf();
            } else if (statement.type == CppStatement.TYPE_FUNCTION) {
                printf();
            }
            boolean classTaken = false;
            if (parent != null && parent.type == CppStatement.TYPE_CLASS) {
                classTaken = true;
                if (currentAccessLevel != statement.accessLevel) {
                    currentAccessLevel = statement.accessLevel;
                    
                    switch (currentAccessLevel) {
                    case CppStatement.ACCESS_LEVEL_PRIVATE:
                        printf(prefix + "private:");
                        break;
                    case CppStatement.ACCESS_LEVEL_PROTECTED:
                        printf(prefix + "protected:");
                        break;
                    case CppStatement.ACCESS_LEVEL_PUBLIC:
                        printf(prefix + "public:");
                        break;
                    case CppStatement.ACCESS_LEVEL_DEFAULT:
                        printf(prefix + "default:// TODO ???");
                        break;
                    }
                }
            }
            writeStatement(classTaken ? prefix + "  " : prefix, statement, parent);
            lastStatement = statement;
        }
    }
    
    private void writeStatement(String prefix, CppStatement target, CppStatement parent) {
        if (target.relatedComment != null) {
            for (int i = 0; i < target.relatedComment.size(); i++) {
                printf(prefix + target.relatedComment.get(i));
            }
        }
        
        CppParagraph paragraph;
        StringBuffer buffer;
        switch (target.type) {
        case CppStatement.TYPE_NAMESPACE:
        case CppStatement.TYPE_NAMESPACE_USING:
            NameSpace nameSpace = (NameSpace) target;
            if (nameSpace.type == CppStatement.TYPE_NAMESPACE_USING) {
                printf(prefix + "using " + nameSpace.name + ";");
            } else {
                printf(prefix + "namespace " + nameSpace.name + " {");
                paragraph = nameSpace.paragraph;
                if (paragraph != null && paragraph.cppStatements != null) {
                    writeStatementList(prefix, paragraph.cppStatements, parent);
                }
                printf(prefix + "}");
            }
            break;
        case CppStatement.TYPE_CLASS:
        case CppStatement.TYPE_STRUCT:
            CppClass cppClass = (CppClass) target;
            
            buffer = new StringBuffer(prefix + (cppClass.isFriend ? "friend " : ""));
            buffer.append(target.type == CppStatement.TYPE_CLASS ? "class " : "struct ");
            buffer.append(cppClass.name);
            if (cppClass.extendedClasses != null) {
                buffer.append(" : ");
                for (int i = 0; i < cppClass.extendedClasses.size(); i++) {
                    if (i > 0) {
                        buffer.append("\n" + prefix + "    ");
                    }
                    buffer.append("public " + cppClass.extendedClasses.get(i));
                    if (i < cppClass.extendedClasses.size() - 1) {
                        buffer.append(",");
                    }
                }
            }
            paragraph = cppClass.paragraph;
            if (paragraph != null) {
                buffer.append(" {");
            } else {
                buffer.append(";");
            }
            printf(buffer.toString());
            if (paragraph != null && paragraph.cppStatements != null) {
                writeStatementList(prefix + "  ", paragraph.cppStatements, cppClass);
                printf(prefix + "};");
            }
            break;
        case CppStatement.TYPE_FIELD:
            writeCppField(prefix, target, parent);
            break;
        case CppStatement.TYPE_FUNCTION:
            writeCppFunction(prefix, target, parent);
            break;
        case CppStatement.TYPE_ENUM:
            CppEnumeration cppEnumeration = (CppEnumeration) target;
            buffer = new StringBuffer();
            if (cppEnumeration.name != null) {
                buffer.append(prefix + "enum " + cppEnumeration.name);
            } else {
                buffer.append(prefix + "enum");
            }
            if (cppEnumeration.paragraph != null && cppEnumeration.paragraph.cppCode != null) {
                buffer.append(" {");
                printf(buffer.toString());
                for (int i = 0; i < cppEnumeration.paragraph.cppCode.size(); i++) {
                    printf(prefix + "    " + cppEnumeration.paragraph.cppCode.get(i));
                }
                printf(prefix + "};");
            } else {
                buffer.append(";");
                printf(buffer.toString());
            }
            break;
        case CppStatement.TYPE_DEFINE:
            break;
        case CppStatement.TYPE_DEFINE_APPLY:
            CppDefinition definition = (CppDefinition) target;
            buffer = new StringBuffer(prefix);
            buffer.append(definition.name + "(");
            ArrayList<String> arguments = definition.arguments;
            for (int i = 0; i < arguments.size(); i++) {
                buffer.append(arguments.get(i));
                if (i < arguments.size() - 1) {
                    buffer.append(", ");
                }
            }
            buffer.append(");");
            printf(buffer.toString());
            break;
        default:
            throw new RuntimeException("Logic crash. type:" + target.type);
        }
    }
    
    private void writeCppField(String prefix, CppStatement target, CppStatement parent) {
        CppField cppField = (CppField) target;
        StringBuffer buffer = new StringBuffer(prefix);
        if (cppField.isStatic) {
            buffer.append("static ");
        }
        if (cppField.isConst) {
            buffer.append("const ");
        }
        
        String oldValue = cppField.value.value;
        cppField.value.value = null;
        buffer.append(cppField.value.toString());
        if (cppField.scope != null) {
            buffer.append(" " + cppField.scope + oldValue);
        } else {
            buffer.append(" " + oldValue);
        }
        cppField.value.value = oldValue;
        
        if (cppField.initedValue != null) {
            buffer.append(" = " + cppField.initedValue + ";");
        } else if (cppField.paragraph != null && cppField.paragraph.cppCode != null) {
            ArrayList<String> cppCode = cppField.paragraph.cppCode;
            if (cppCode.size() == 1) {
                buffer.append(" = {" + cppField.paragraph.cppCode.get(0) + "};");
            } else if (cppCode.size() > 1) {
                buffer.append(" = {\n");
                for (int i = 0; i < cppField.paragraph.cppCode.size(); i++) {
                    buffer.append(prefix + "    " + cppField.paragraph.cppCode.get(i) + "\n");
                }
                buffer.append(prefix + "    };");
            } else {
                buffer.append(";");
            }
        } else {
            buffer.append(";");
        }
        printf(buffer.toString());
    }

    private void writeCppFunction(String prefix, CppStatement target, CppStatement parent) {
        CppFunction function = (CppFunction) target;
        StringBuffer buffer = new StringBuffer(prefix);
        if (function.isStatic) {
            buffer.append("static ");
        }
        if (function.isInline) {
            buffer.append("inline ");
        }
        if (function.isVirtual) {
            buffer.append("virtual ");
        }
        if (function.isSynchronized) {
            buffer.append("/*synchronized*/ ");
        }
        if (function.isNative) {
            buffer.append("/*native*/ ");
        }
        if (!function.isConstructor && !function.isDestructor) {
            buffer.append(function.returnType + " ");
        }
        if (function.scope != null) {
            buffer.append(function.scope);
        }
        buffer.append((function.isDestructor ? "~" : "") + function.name + "(");
        if (function.parameters != null) {
            for (int i = 0; i < function.parameters.size(); i++) {
                buffer.append(function.parameters.get(i));
                if (i < function.parameters.size() - 1) {
                    buffer.append(", ");
                }
            }
        }
        buffer.append(")");
        if (function.isConstructor) {
            boolean colonMet = false;
            if (function.parentNames != null) {
                buffer.append("\n" + prefix + "   :   ");
                colonMet = true;
                for (int i = 0; i < function.parentNames.size(); i++) {
                    String parentConstructorName = function.parentNames.get(i);
                    buffer.append(parentConstructorName + "(");
                    ArrayList<String> parentParameters = function.parentParameterMap != null ?
                            function.parentParameterMap.get(parentConstructorName) : null;
                    if (parentParameters != null) {
                        for (int j = 0; j < parentParameters.size(); j++) {
                            String parentPara = parentParameters.get(j);
                            buffer.append(parentPara);
                            if (j < parentParameters.size() - 1) {
                                buffer.append(", ");
                            }
                        }
                    }
                    buffer.append(")");
                    if (i < function.parentNames.size() - 1) {
                        buffer.append(", ");
                    }
                }
            }
            if (function.initedMap != null) {
                boolean firstIterate = true;
                for (Entry<String, String> entry : function.initedMap.entrySet()) {
                    String initedPara = entry.getKey();
                    String initedValue = entry.getValue();
                    buffer.append((firstIterate && !colonMet ? ":\n" : ",\n") + prefix +
                            "    " + initedPara + "(" + initedValue + ")");
                    firstIterate = false;
                }
            }
        } else {
            if (function.isConst) {
                buffer.append(" const");
            }
            if (function.isPurelyVirtual) {
                buffer.append(" = 0");
            }
            if (function.isOverride) {
                buffer.append(" override");
            }
        }
        if (function.paragraph != null) {
            buffer.append("\n" + prefix + "{");//\n" + prefix + "    \n" + prefix + "}"
            printf(buffer.toString());
            if (function.paragraph.cppCode != null) {
                writeCodeParagraph(prefix + "    ", function.paragraph);
            }
            printf(prefix + "}");
        } else {
            buffer.append(";");
            printf(buffer.toString());
        }
    }
    
    private void writeCodeParagraph(String prefix, CppParagraph paragraph) {
        paragraph.write(prefix, CONSOLE_OUTPUT ? System.out : mOut);
    }
    
    private void printf() {
        if (CONSOLE_OUTPUT) {
            System.out.println();
        } else {
            mOut.println();
        }
    }
    
    private void printf(String line) {
        if (CONSOLE_OUTPUT) {
            System.out.println(line);
        } else {
            mOut.println(line);
        }
    }
    
    public static class BaseFile {
        String path;
        String name;
        ArrayList<String> includings = new ArrayList<>();
        ArrayList<String> includingTags = new ArrayList<>();
        
        int usingHeaderIndex;
        ArrayList<CppStatement> cppStatements = new ArrayList<>();
        
        void addCustomHeaderIfNecessary(CppStatement statement) {
            cppStatements.add(usingHeaderIndex++, statement);
        }
    }
    
    public static class ChFile extends BaseFile {
    }
    
    public static class CppFile extends BaseFile {
    }
    
    static class CppParagraph {
        static final int TYPE_NAMESPACE = 0;
        static final int TYPE_CLASS = 1;
        static final int TYPE_STRUCT = 2;
        static final int TYPE_FUNCTION = 3;
        static final int TYPE_ENUM = 4;
        static final int TYPE_CODE = 5;
        
        int type;
        
        CppStatement owner;
        ArrayList<CppStatement> cppStatements;
        ArrayList<String> cppCode;
        
        void addCppStatement(CppStatement statement) {
            addCppStatement(statement, statement.order);
        }
        
        public void write(String prefix, PrintStream out) {
            for (int i = 0; i < cppCode.size(); i++) {
                out.println(prefix + cppCode.get(i));
            }
        }

        void addCppStatement(CppStatement statement, int order) {
            if (cppStatements == null) {
                cppStatements = new ArrayList<>();
            }
            if (order == Integer.MAX_VALUE) {
                cppStatements.add(statement);
            } else {
                int insertIndex = 0;
                boolean found = false;
                for (int i = 0; i < cppStatements.size(); i++) {
                    CppStatement childStatement = cppStatements.get(i);
                    if (childStatement.order > order) {
                        insertIndex = i;
                        found = true;
                        break;
                    }
                }
                if (found) {
                    cppStatements.add(insertIndex, statement);
                } else {
                    cppStatements.add(statement);
                }
            }
            statement.parentStatement = owner;
        }
        
//        void addCppStatement(int index, CppStatement statement, int order) {
//            if (cppStatements == null) {
//                cppStatements = new ArrayList<>();
//            }
//            cppStatements.add(index, statement);
//            statement.parentStatement = owner;
//        }
        
        void addCode(String code) {
            if (cppCode == null) {
                cppCode = new ArrayList<>();
            }
            cppCode.add(code);
        }
    }
    
    static class CppClassParagraph extends CppParagraph {
        ArrayList<CppStatement> defaultStatements;
        ArrayList<CppStatement> publicStatements;
        ArrayList<CppStatement> privateStatements;
        ArrayList<CppStatement> protectedStatements;
        {
            type = TYPE_CLASS;
        }
        
        @Override
        void addCppStatement(CppStatement statement) {
            super.addCppStatement(statement);
            switch (statement.accessLevel) {
            case CppStatement.ACCESS_LEVEL_PUBLIC:
                if (publicStatements == null) {
                    publicStatements = new ArrayList<>();
                }
                publicStatements.add(statement);
                break;
            case CppStatement.ACCESS_LEVEL_PRIVATE:
                if (privateStatements == null) {
                    privateStatements = new ArrayList<>();
                }
                privateStatements.add(statement);
                break;
            case CppStatement.ACCESS_LEVEL_PROTECTED:
                if (protectedStatements == null) {
                    protectedStatements = new ArrayList<>();
                }
                protectedStatements.add(statement);
                break;
            case CppStatement.ACCESS_LEVEL_DEFAULT:
                if (defaultStatements == null) {
                    defaultStatements = new ArrayList<>();
                }
                defaultStatements.add(statement);
                break;
            }
        }
    }
    
    static class CppCodeParagraph extends CppParagraph {
        CodeParagraph codeParagraph;
        
        @Override
        public void write(String prefix, PrintStream out) {
            if (codeParagraph != null) {
                codeParagraph.write(prefix, out);
            } else {
                super.write(prefix, out);
            }
        }
    }
    
    static class CppStatement {
        static final int TYPE_NAMESPACE = 0;
        static final int TYPE_NAMESPACE_USING = 1;
        static final int TYPE_CLASS = 2;
        static final int TYPE_STRUCT = 3;
        static final int TYPE_FIELD = 4;
        static final int TYPE_FUNCTION = 5;
        static final int TYPE_ENUM = 6;
        static final int TYPE_DEFINE = 7;
        static final int TYPE_DEFINE_APPLY = 8;
        
        int type;
        int order = Integer.MAX_VALUE;
        boolean isAutoGenerated;
        String name;
        CppParagraph paragraph;
        CppStatement parentStatement;
        ArrayList<String> relatedComment;
        
        static final int ACCESS_LEVEL_DEFAULT = 0;
        static final int ACCESS_LEVEL_PUBLIC = 1;
        static final int ACCESS_LEVEL_PROTECTED = 2;
        static final int ACCESS_LEVEL_PRIVATE = 3;
        
        static int convertJaveAccessLevel2Cpp(int javaLevel) {
            switch (javaLevel) {
            case JavaStatement.LEVEL_DEFAULT:
            case JavaStatement.LEVEL_PUBLIC:
                return ACCESS_LEVEL_PUBLIC;
            case JavaStatement.LEVEL_PROTECTED:
                return ACCESS_LEVEL_PROTECTED;
            case JavaStatement.LEVEL_PRIVATE:
                return ACCESS_LEVEL_PRIVATE;
            default:
                throw new RuntimeException("Invalid level:" + javaLevel);
            }
        }
        
        int accessLevel = ACCESS_LEVEL_PUBLIC;
        
        CppStatement() {
        }
        
        CppStatement(CppStatement another) {
            type = another.type;
            order = another.order;
            name = another.name;
            accessLevel = another.accessLevel;
            paragraph = another.paragraph;
            isAutoGenerated = another.isAutoGenerated;
            relatedComment = another.relatedComment;
        }
        
        static final ArrayList<String> sDefaultComment = new ArrayList<>();
        static {
            sDefaultComment.add("/**");
            sDefaultComment.add(" * auto generated");
            sDefaultComment.add(" */");
        }
        
        void generateDefaultComment() {
            isAutoGenerated = true;
            relatedComment = new ArrayList<>(sDefaultComment);
        }
        
        void dropCommentIfNotAutoGenerated() {
            if (!isAutoGenerated) {
                relatedComment = null;
            }
        }
        
        CppParagraph createParagraphIfNeeded() {
            if (paragraph == null) {
                if (type == TYPE_CLASS || type == TYPE_STRUCT) {
                    paragraph = new CppClassParagraph();
                } else if (type == TYPE_FUNCTION) {
                    paragraph = new CppCodeParagraph();
                } else {
                    paragraph = new CppParagraph();
                }
                switch (type) {
                case TYPE_NAMESPACE:
                    paragraph.type = CppParagraph.TYPE_NAMESPACE;
                    break;
                case TYPE_CLASS:
                    paragraph.type = CppParagraph.TYPE_CLASS;
                    break;
                case TYPE_FUNCTION:
                    paragraph.type = CppParagraph.TYPE_FUNCTION;
                    break;
                case TYPE_ENUM:
                    paragraph.type = CppParagraph.TYPE_ENUM;
                    break;
                case TYPE_STRUCT:
                    paragraph.type = CppParagraph.TYPE_STRUCT;
                    break;
                default:
                    paragraph.type = CppParagraph.TYPE_CODE;
                    break;
                }
                paragraph.owner = this;
            }
            return paragraph;
        }
        
        void apply(JavaStatement javaStatement) {
            this.order = javaStatement.line;
            this.name = javaStatement.name;
            this.accessLevel = convertJaveAccessLevel2Cpp(javaStatement.accessLevel);
            this.relatedComment = javaStatement.relatedComment;
            this.isAutoGenerated = javaStatement.isAutoGenerated;
            if (isAutoGenerated) {
                generateDefaultComment();
            }
        }
    }
    
    static class NameSpace extends CppStatement {
        boolean using;
        {
            type = TYPE_NAMESPACE;
        }
    }
    
    static class CppClass extends CppStatement {
        
        ArrayList<String> templates;
        
        boolean isFriend;
        
        ArrayList<String> extendedClasses;
        
        {
            type = TYPE_CLASS;
        }
        
        @Override
        CppClassParagraph createParagraphIfNeeded() {
            return (CppClassParagraph) super.createParagraphIfNeeded();
        }
    }
    
    enum VAL_CATE {
        VAL,
        REF,
        POINTER
    }
    
    static class ParcelableHelper {
        
        static ParcelableSolution resolveParcelableSolution(TypedValue parameter) {
            if ("IBinder".equals(parameter.oldName)) {
                return ParcelableSolution.RAW_BINDER;
            } else if (parameter.isString) {
                return ParcelableSolution.STRING;
            } else if (JavaField.ATOM_TYPE_INT.equals(parameter.oldName)) {
                return ParcelableSolution.ATOM_INT;
            } else if (JavaField.ATOM_TYPE_LONG.equals(parameter.oldName)) {
                return ParcelableSolution.ATOM_INT64;
            } else if (JavaField.ATOM_TYPE_BOOLEAN.equals(parameter.oldName)) {
                return ParcelableSolution.ATOM_BOOL;
            } else if (JavaField.ATOM_TYPE_FLOAT.equals(parameter.oldName)) {
                return ParcelableSolution.ATOM_FLOAT;
            } else if (JavaField.ATOM_TYPE_DOUBLE.equals(parameter.oldName)) {
                return ParcelableSolution.ATOM_DOUBLE;
            } else if (JavaField.ATOM_TYPE_BYTE.equals(parameter.oldName)) {
                return ParcelableSolution.ATOM_BYTE;
            } else if (parameter.name.startsWith("ArrayList")) {
                return ParcelableSolution.ARRAYLIST;
            } else if (!"Intent".equals(parameter.oldName) && parameter.oldName.startsWith("I")) {
                return ParcelableSolution.WRAPPED_BINDER;
            } else {
                return ParcelableSolution.PARCELABLE;
            }/* else {
                throw new RuntimeException(parameter.name + " can not be parcelled");
            }*/
        }
        
        static void obtainParcelInStatement(TypedValue parameter, String optionArg,
                boolean inData, ArrayList<String> result) {
            ParcelableSolution solution = parameter.resolveParcelableSolution();
            String argument = optionArg != null ? optionArg : parameter.value;
            String in = inData ? "_data" : "_reply";
            String invo = in + (inData ? "." : "->");
            result.clear();
            switch (solution) {
            case RAW_BINDER:
                result.add(invo + "writeStrongBinder(" + argument + ");");
                break;
            case WRAPPED_BINDER:
                result.add(invo + "writeStrongBinder((" + argument + " != nullptr) ? " +
                        argument + "->asBinder() : nullptr);");
                break;
            case STRING:
                result.add(invo + "writeString(" + argument + ");");
                break;
            case ATOM_INT:
                result.add(invo + "writeInt(" + argument + ");");
                break;
            case ATOM_INT64:
                result.add(invo + "writeLong(" + argument + ");");
                break;
            case ATOM_BOOL:
                result.add(invo + "writeInt(" + argument + " ? 1 : 0);");
                break;
            case ATOM_FLOAT:
                result.add(invo + "writeFloat(" + argument + ");");
                break;
            case ATOM_DOUBLE:
                result.add(invo + "writeDouble(" + argument + ");");
                break;
            case ATOM_BYTE:
                result.add(invo + "writeByte(" + argument + ");");
                break;
            case ARRAYLIST:
                result.add(invo + "writeTypedList(" + argument + ");");
                break;
            case PARCELABLE:
                result.add("if (" + argument + " != nullptr) {");
                result.add("    " + invo + "writeInt(1);");
                result.add("    " + argument + "->writeToParcel(" + (inData ? "" : "*") + in + ", 0);");
                result.add("} else {");
                result.add("    " + invo + "writeInt(0);");
                result.add("}");
                break;
            case EXCEPTION:
            default:
                throw new RuntimeException("unexpected solution:" + solution);
            }
        }
        
        static void obtainParcelOutStatement(TypedValue parameter, String optionArg,
                boolean fromData, boolean withDeclearation, ArrayList<String> result) {
            ParcelableSolution solution = parameter.resolveParcelableSolution();
            String from = fromData ? "_data" : "_reply";
            String argument = optionArg != null ? optionArg : parameter.value;
            String declearation;
            result.clear();
            switch (solution) {
            case RAW_BINDER:
                declearation = withDeclearation ? "sp<IBinder> " : "";
                result.add(declearation + argument + " = " + from + ".readStrongBinder();");
                break;
            case WRAPPED_BINDER:
                declearation = withDeclearation ? "sp<" + parameter.oldName + "> " : "";
                result.add(declearation + argument + " = " + parameter.oldName +
                        "::Stub::asInterface(_data.readStrongBinder());");
                break;
            case STRING:
                declearation = withDeclearation ? "String " : "";
                result.add(declearation + argument + " = " + from + ".readString();");
                break;
            case ATOM_INT:
                declearation = withDeclearation ? CppField.ATOM_TYPE_INT + " " : "";
                result.add(declearation + argument + " = " + from + ".readInt();");
                break;
            case ATOM_INT64:
                declearation = withDeclearation ? CppField.ATOM_TYPE_INT64 + " " : "";
                result.add(declearation + argument + " = " + from + ".readLong();");
                break;
            case ATOM_BOOL:
                declearation = withDeclearation ? CppField.ATOM_TYPE_BOOL + " " : "";
                result.add(declearation + argument + " = (" + from + ".readInt() != 0);");
                break;
            case ATOM_FLOAT:
                declearation = withDeclearation ? CppField.ATOM_TYPE_FLOAT + " " : "";
                result.add(declearation + argument + " = " + from + ".readFloat();");
                break;
            case ATOM_DOUBLE:
                declearation = withDeclearation ? CppField.ATOM_TYPE_DOUBLE + " " : "";
                result.add(declearation + argument + " = " + from + ".readDouble();");
                break;
            case ATOM_BYTE:
                declearation = withDeclearation ? CppField.ATOM_TYPE_CHAR + " " : "";
                result.add(declearation + argument + " = " + from + ".readByte();");
                break;
            case PARCELABLE:
                if (withDeclearation) {
                    result.add("sp<" + parameter.oldName + "> " + argument + ";");
                }
                result.add("if (0 != _data.readInt()) {");
                result.add("    " + argument + " = " + parameter.oldName +
                        "::CREATOR.createFromParcel(_data);");
                result.add("}");
                break;
            case ARRAYLIST:
                String oldArgs = parameter.value;
                parameter.value = null;
                declearation = withDeclearation ? parameter.toString() + " " : "";
                parameter.value = oldArgs;
                if (parameter.templateTypes == null || parameter.templateTypes.size() > 1) {
                    throw new RuntimeException("Logic crash");
                }
                String creatorName = parameter.templateTypes.get(0).oldName + "::CREATOR";
                result.add(declearation + argument + " = " + from + ".createTypedArrayList(" + 
                        creatorName + ");");
                break;
            case EXCEPTION:
            default:
                throw new RuntimeException("unexpected solution:" + solution);
            }
        }
    }
    
    enum ParcelableSolution {
        NONE,
        RAW_BINDER,
        WRAPPED_BINDER,
        STRING,
        ATOM_INT,
        ATOM_INT64,
        ATOM_BOOL,
        ATOM_FLOAT,
        ATOM_DOUBLE,
        ATOM_BYTE,
        ARRAYLIST,
        PARCELABLE,
        EXCEPTION
//        FLATTENABLE
    }
    
    static class TypedValue {
        String oldName;
        String name;
        VAL_CATE type;
        String value;
        
        boolean isSp;
        boolean isString;
        
        boolean isConst;
        boolean isDataContainer;
        boolean isArray;
        
        boolean isIn;
        boolean isOut;
        
        boolean isVoidable;
        String defaultValue;
        
        ParcelableSolution resolvedParcelSolution = ParcelableSolution.NONE;
        
        ArrayList<TypedValue> templateTypes;
        
        static TypedValue obtainCppTypedValue(String javaType) {
            return obtainCppTypedValue(javaType, false);
        }
        
        static TypedValue obtainCppTypedValue(String javaType, boolean pointer) {
//            String suffix = "";
//            while (javaType.endsWith("[]")) {
//                javaType = javaType.substring(0, javaType.length() - 2);
//                suffix += "[]";
//            }
            if ("void".equals(javaType)) {
                return new TypedValue("void");
            } else if (javaType.endsWith("[]")) {
                return processJavaArray(javaType);
            } else if (JavaField.isAtomType(javaType)) {
                return new TypedValue(javaType, getCppAtomTypeByJava(javaType), VAL_CATE.VAL);
            } else if ("String".equals(javaType)) {
                TypedValue tv = new TypedValue("String");
                tv.isString = true;
                return tv;
            } else if (JavaField.isDataStructureClass(javaType)) {
                return processJavaUtilStructure(javaType);
            } else {
                if (!pointer) {
                    TypedValue tv = new TypedValue(javaType, "sp<" + javaType + ">", VAL_CATE.VAL);
                    tv.isSp = true;
                    return tv;
                } else {
                    return new TypedValue(javaType, VAL_CATE.POINTER);
                }
            }
        }
        
        private static TypedValue processJavaArray(String javaType) {
            while (javaType.endsWith("[]")) {
                javaType = javaType.substring(0, javaType.length() - 2);
            }
            TypedValue tv = new TypedValue(javaType, "Array", VAL_CATE.VAL);
            tv.templateTypes = new ArrayList<>();
            if (JavaField.isAtomType(javaType)) {
                tv.templateTypes.add(obtainCppTypedValue(getCppAtomTypeByJava(javaType)));
            } else {
                tv.templateTypes.add(obtainCppTypedValue(javaType));
            }
            tv.isDataContainer = true;
            tv.isArray = true;
            return tv;
        }
        
        private static TypedValue processJavaUtilStructure(String javaType) {
            int leftIndex = javaType.indexOf('<');
            TypedValue tv = new TypedValue(javaType, null, VAL_CATE.VAL);
            ArrayList<TypedValue> middleList = null;
            String realTypeName = javaType;
            if (leftIndex >= 0) {
                int rightIndex = javaType.lastIndexOf('>');
                realTypeName = javaType.substring(0, leftIndex);
                String middleType = javaType.substring(leftIndex + 1, rightIndex);
                String[] middleSigil = middleType.split(",");
                int leftAnchor = 0;
                String tempBuffer = "";
                for (int i = 0; i < middleSigil.length; i++) {
                    String sigil = middleSigil[i].trim();
                    leftAnchor += JavaReader.countOfSpecifiedChar(sigil, '<');
                    leftAnchor -= JavaReader.countOfSpecifiedChar(sigil, '>');
                    if (leftAnchor > 0) {
                        tempBuffer += sigil;
                        continue;
                    }
                    if (tempBuffer.length() > 0) {
                        sigil = tempBuffer + "," + sigil;
                        tempBuffer = "";
                    }
                    if (sigil.length() > 0) {
                        if (middleList == null) {
                            middleList = new ArrayList<>();
                        }
                        middleList.add(obtainCppTypedValue(sigil, false));
                    }
                }
            }
            tv.templateTypes = middleList;
            if (realTypeName.equals("List")) {
                realTypeName = "ArrayList";
            } else if (realTypeName.equals("Map")) {
                realTypeName = "HashMap";
            }
            tv.name = realTypeName;
            tv.isDataContainer = true;
            return tv;
        }
        
        TypedValue(String name) {
            this(name, name, VAL_CATE.VAL);
        }
        
        TypedValue(TypedValue copy) {
            this(copy.oldName, copy.name, copy.type);
            this.isDataContainer = copy.isDataContainer;
            this.isArray = copy.isArray;
            this.isSp = copy.isSp;
            this.templateTypes = copy.templateTypes;
        }
        
        TypedValue(String name, VAL_CATE type) {
            this(name, name, type);
        }
        
        TypedValue(String oldName, String name, VAL_CATE type) {
            this.oldName = oldName;
            this.name = name;
            this.type = type;
        }
        
        void collectClassName(UnseenClassHelper helper) {
            if (isArray) {
                helper.addClass(oldName);
            } else if (isDataContainer) {
                helper.addClass(name);
                if (templateTypes != null) {
                    for (int i = 0; i < templateTypes.size(); i++) {
                        TypedValue tv = templateTypes.get(i);
                        tv.collectClassName(helper);
                    }
                }
            } else if (isString) {
                helper.addClass("String");
            } else {
                helper.addClass(oldName);
            }
        }
        
        ParcelableSolution resolveParcelableSolution() {
            if (resolvedParcelSolution == ParcelableSolution.NONE) {
                resolvedParcelSolution = ParcelableHelper.resolveParcelableSolution(this);
            }
            return resolvedParcelSolution;
        }
        
        @Override
        public String toString() {
            StringBuffer temp = new StringBuffer();
            if (isConst) {
                temp.append("const ");
            }
            temp.append(name);
            if (templateTypes != null && templateTypes.size() > 0) {
                temp.append('<');
                for (int i = 0; i < templateTypes.size(); i++) {
                    temp.append(templateTypes.get(i).toString());
                    if (i < templateTypes.size() - 1) {
                        temp.append(", ");
                    }
                }
                temp.append('>');
            }
//            if (isArray) {
//                temp.append("[]");
//            }
            switch (type) {
            case REF:
                temp.append("&");
                break;
            case POINTER:
                temp.append("*");
                break;
            case VAL:
                break;
            }
            if (value != null) {
                temp.append(" " + value);
            }
            if (isVoidable && defaultValue != null) {
                temp.append(" = " + defaultValue);
            }
            return temp.toString();
        }
    }
    
    static class CppField extends CppStatement {
        
        static final String ATOM_TYPE_CHAR = "char";
        static final String ATOM_TYPE_SHORT = "short";
        static final String ATOM_TYPE_INT = "int";
        static final String ATOM_TYPE_INT32 = "int32_t";
        static final String ATOM_TYPE_INT64 = "int64_t";
        static final String ATOM_TYPE_UINT32 = "uint32_t";
        static final String ATOM_TYPE_FLOAT = "float";
        static final String ATOM_TYPE_DOUBLE = "double";
//        static final String ATOM_TYPE_LONG = "long";
        static final String ATOM_TYPE_BOOL = "bool";
        
        static boolean isAtomType(String type) {
            while (type.endsWith("[]")) {
                type = type.substring(0, type.length() - 2);
            }
            switch (type) {
            case ATOM_TYPE_CHAR:
            case ATOM_TYPE_SHORT:
            case ATOM_TYPE_INT:
            case ATOM_TYPE_INT32:
            case ATOM_TYPE_INT64:
            case ATOM_TYPE_UINT32:
            case ATOM_TYPE_FLOAT:
            case ATOM_TYPE_DOUBLE:
//            case ATOM_TYPE_LONG:
            case ATOM_TYPE_BOOL:
                return true;
            }
            return false;
        }
        
        static String getDefaultCppValue(String type) {
            switch (type) {
            case ATOM_TYPE_CHAR:
            case ATOM_TYPE_SHORT:
            case ATOM_TYPE_INT:
            case ATOM_TYPE_INT32:
            case ATOM_TYPE_INT64:
            case ATOM_TYPE_UINT32:
            case ATOM_TYPE_FLOAT:
            case ATOM_TYPE_DOUBLE:
//            case ATOM_TYPE_LONG:
                return "0";
            case ATOM_TYPE_BOOL:
                return "false";
            }
            throw new RuntimeException("Invalid type:" + type);
        }
        
        String scope;
        
        boolean isStatic;
        boolean isConst;
        boolean isVolatile;
        boolean isMutable;
        
        TypedValue value;
        String initedValue;
        
        {
            type = TYPE_FIELD;
        }
    }
    
    static class CppFunction extends CppStatement {
        
        String scope;
        
        boolean isStatic;
        boolean isInline;
        boolean isConst;
        boolean isVirtual;
        // only available when isVirtual is true
        boolean isPurelyVirtual;
        
        boolean isConstructor;
        boolean isDestructor;
        
        boolean isOverride;
        boolean isSynchronized;
        boolean isNative;
        
        TypedValue returnType;
        ArrayList<String> templates;
        
        ArrayList<TypedValue> parameters;
        
        // only available when isConstructor is true
        ArrayList<String> parentNames;
        HashMap<String, ArrayList<String>> parentParameterMap;
//        ArrayList<String> parentParameters;
        HashMap<String, String> initedMap;
        
        {
            type = TYPE_FUNCTION;
        }
        
        CppFunction() {
        }
        
        CppFunction(CppFunction another) {
            super(another);
            scope = another.scope;
            isStatic = another.isStatic;
            isInline = another.isInline;
            isConst = another.isConst;
            isVirtual = another.isVirtual;
            isPurelyVirtual = another.isPurelyVirtual;
            isConstructor = another.isConstructor;
            isDestructor = another.isDestructor;
            isOverride = another.isOverride;
            isSynchronized = another.isSynchronized;
            isNative = another.isNative;
            returnType = another.returnType;
            templates = another.templates;
            
            parameters = another.parameters;
            parentNames = another.parentNames != null ? new ArrayList<>(parentNames) : null;
            parentParameterMap = another.parentParameterMap != null ?
                    new HashMap<>(parentParameterMap) : null;
            initedMap = another.initedMap != null ? new HashMap<>(initedMap) : null;    
        }
        
        boolean hasReturnType() {
            if (returnType == null) {
                return false;
            }
            if ("void".equals(returnType.name)) {
                return false;
            }
            return true;
        }
        
        void addParentConstructor(String name, String... params) {
            if (parentNames == null) {
                parentNames = new ArrayList<>();
            }
            parentNames.add(name);
            if (params != null && params.length > 0) {
                if (parentParameterMap == null) {
                    parentParameterMap = new HashMap<>();
                }
                ArrayList<String> parameters = new ArrayList<>();
                for (int i = 0; i < params.length; i++) {
                    parameters.add(params[i]);
                }
                parentParameterMap.put(name, parameters);
            }
        }
    }
    
    static class CppEnumeration extends CppStatement {
        {
            type = TYPE_ENUM;
        }
    }
    
    static class CppDefinition extends CppStatement {
        ArrayList<String> arguments = new ArrayList<>();
        ArrayList<String> replacesCode;
        {
            type = TYPE_DEFINE_APPLY;
        }
    }
}
