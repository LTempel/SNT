#!/bin/bash
VERSION=3.1.104
cd ~/code/morphonets/SNT;
mvn package;
/bin/cp -rf ./target/SNT-$VERSION.jar ~/code/Fijis/FijiNeuroanatomyUpload.app/jars/;
~/code/Fijis/FijiNeuroanatomyUpload.app/ImageJ-linux64