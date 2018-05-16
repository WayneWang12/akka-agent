# Builder container
FROM registry.cn-hangzhou.aliyuncs.com/aliware2018/services AS builder

# Install sbt and cache dependencies.
RUN echo 'deb http://mirrors.aliyun.com/debian/ stretch main non-free contrib\n \
          deb http://mirrors.aliyun.com/debian/ stretch-proposed-updates main non-free contrib\n \
          deb-src http://mirrors.aliyun.com/debian/ stretch main non-free contrib\n \
          deb-src http://mirrors.aliyun.com/debian/ stretch-proposed-updates main non-free contrib' | tee /etc/apt/sources.list
RUN apt-get update -y && apt-get install -y apt-transport-https
RUN echo "deb https://dl.bintray.com/sbt/debian /" | tee -a /etc/apt/sources.list.d/sbt.list
RUN apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823
RUN apt-get update -y  && apt-get install -y sbt
RUN mkdir -p /root/.sbt
COPY ./repositories /root/.sbt
RUN git clone https://github.com/WayneWang12/akka-sample.git && cd akka-sample  && sbt compile

COPY . /root/workspace/agent
WORKDIR /root/workspace/agent
RUN set -ex && git show -s --format=%B &&  sbt clean compile assembly


# Runner container
FROM registry.cn-hangzhou.aliyuncs.com/aliware2018/debian-jdk8

COPY --from=builder /root/workspace/services/mesh-provider/target/mesh-provider-1.0-SNAPSHOT.jar /root/dists/mesh-provider.jar
COPY --from=builder /root/workspace/services/mesh-consumer/target/mesh-consumer-1.0-SNAPSHOT.jar /root/dists/mesh-consumer.jar
COPY --from=builder /root/workspace/agent/mesh-agent/target/scala-2.12/mesh-agent-assembly-1.0-SNAPSHOT.jar /root/dists/mesh-agent.jar

COPY --from=builder /usr/local/bin/docker-entrypoint.sh /usr/local/bin
COPY ./start-agent.sh /usr/local/bin

RUN set -ex && mkdir -p /root/logs && chmod 777 /usr/local/bin/start-agent.sh

ENTRYPOINT ["docker-entrypoint.sh"]
