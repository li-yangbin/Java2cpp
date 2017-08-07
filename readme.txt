I write this program to help me build c++ wifi framework on Android platflorm.
It works well in most situations


How to use?

1 Declare your java file path and c++ output directory, String path = ....., String outDir = .....
2 Call JavaFile javafile = new JavaReader().read(path) to generate a JavaFile object
3 Call new CppWrite().write(javafile, outDir) to generate c++ code
4 Also, you can turn DEBUGMODE flag to true to generate c++ code in console
5 It might failed in some situations... contact me if you need help

My email: lyb928@qq.com
