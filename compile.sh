#!/bin/bash

echo "Cleaning up..."
find . -name "*.class" -exec rm {} \;
echo "Compiling exception list processor..."
javac -Xlint:unchecked -encoding utf-8 exceptiondefs/ExceptionDefProcessor.java || exit 1
echo "Transforming exceptions list..."
java exceptiondefs.ExceptionDefProcessor exceptiondefs/exceptions.def || exit 1
echo "Compiling main application..."
javac -Xlint:unchecked -encoding utf-8 JPCApplication.java || exit 1
echo "Compiling ImageMaker..."
javac -Xlint:unchecked -encoding utf-8 ImageMaker.java || exit 1
echo "Compiling plugins..."
javac -Xlint:unchecked -encoding utf-8 org/jpc/plugins/*.java || exit 1
echo "Compiling modules..."
javac -Xlint:unchecked -encoding utf-8 org/jpc/modules/*.java || exit 1
echo "Compiling Lua extensions..."
javac -Xlint:unchecked -encoding utf-8 org/jpc/luaextensions/*.java || exit 1
echo "Compling streamtools..."
cd streamtools && make
echo "Done."
