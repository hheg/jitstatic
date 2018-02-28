FROM frolvlad/alpine-oraclejre8:full

LABEL maintainer="H.Hegardt <henrikhegardtgithub@gmail.com>"

ARG version
ENV JITSTATIC_VERSION ${version}
ENV WORK_DIR /home/jitstatic
ENV VOL ${WORK_DIR}/db
ENV JITSTATIC_FILE jitstatic-${JITSTATIC_VERSION}.jar
ENV JITSTATIC_CONF config.yml

WORKDIR ${WORK_DIR}

ADD jitstatic/target/${JITSTATIC_FILE} ${JITSTATIC_FILE}

ADD docker_dw_setup.yml ${JITSTATIC_CONF}

VOLUME ${VOL} 

EXPOSE 8085

ENTRYPOINT exec java \
	-Ddw.hosted.basePath=${VOL} \
	-Ddw.hosted.userName=${USER} \
	-Ddw.hosted.secret=${PASS} \
	-jar ${JITSTATIC_FILE} \
	server \
	${JITSTATIC_CONF}