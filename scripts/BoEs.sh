#!/bin/bash

datestring=$(date +\%Y_\%m_\%d_\%H\%M)
filename=BoE_${1}_${datestring}

java -cp .:/data/wow/build/libs/* com.jkoh.wow.BoEs $2 > /usr/share/tomcat8/webapps/ROOT/wow/${filename} 2>> error.log

grep 'Started at\|Finished at\|^$\|=====\|Item Level 8[5-9]' /usr/share/tomcat8/webapps/ROOT/wow/${filename} | grep -A1 -B1 --no-group-separator 'Started at\|Finished at\|Item Level 8[5-9]' > /usr/share/tomcat8/webapps/ROOT/wow/${filename}_850+

grep 'Started at\|Finished at\|^$\|=====\|Scales with level.*Level:9[8-9]' /usr/share/tomcat8/webapps/ROOT/wow/${filename} | grep -A1 -B1 --no-group-separator 'Started at\|Finished at\|Scales with level.*Level:9[8-9]' > /usr/share/tomcat8/webapps/ROOT/wow/${filename}_99s
