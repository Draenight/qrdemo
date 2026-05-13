Need maven to run. 
I run it with Java 11

if some class are not found run : mvn clean dependency:copy-dependencies package 

Compile with : mvn compiler:compile -f ".\pom.xml"   Or with the full path to the pom.xml

And run with : java -cp "target/classes;target/dependency/*" com.qrdemo.Main.