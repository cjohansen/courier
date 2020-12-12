test:
	clojure -A:dev:test

courier.jar: src/courier/*.*
	clojure -A:jar

clean:
	rm -fr target courier.jar

deploy: courier.jar
	mvn deploy:deploy-file -Dfile=courier.jar -DrepositoryId=clojars -Durl=https://clojars.org/repo -DpomFile=pom.xml

.PHONY: test deploy clean
