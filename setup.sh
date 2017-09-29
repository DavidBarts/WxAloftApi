#!/bin/bash

export JRE_HOME="$(/usr/libexec/java_home)"

export CATALINA_HOME="$HOME/java/apache-tomcat-8.0.46"
if [[ "$PATH" != *$CATALINA_HOME/bin* ]]
then
    export PATH="$CATALINA_HOME/bin:$PATH"
fi

export ANT_HOME="$HOME/java/apache-ant-1.10.1"
if [[ "$PATH" != *$ANT_HOME/bin* ]]
then
    export PATH="$ANT_HOME/bin:$PATH"
fi
