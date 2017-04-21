package org.template;


import javafx.util.Pair;
import org.apache.mahout.math.Vector;
import org.apache.mahout.sparkbindings.SparkDistributedContext;
import org.apache.mahout.sparkbindings.drm.CheckpointedDrmSpark;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.broadcast.Broadcast;
import org.json4s.JsonAST;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.template.indexeddataset.BiDictionaryJava;
import org.template.indexeddataset.IndexedDatasetJava;
import scala.Tuple2;
import scala.reflect.ClassTag;

import java.util.*;


/** Utility conversions for IndexedDatasetSpark */
public class Conversions {

    /**
     * Print stylized ActionML title
     * @param logger Logger to print with
     */
    public static void drawActionML(Logger logger) {
        final String actionML = "" +
                "\n\t" +
                "\n\t               _   _             __  __ _" +
                "\n\t     /\\       | | (_)           |  \\/  | |" +
                "\n\t    /  \\   ___| |_ _  ___  _ __ | \\  / | |" +
                "\n\t   / /\\ \\ / __| __| |/ _ \\| '_ \\| |\\/| | |" +
                "\n\t  / ____ \\ (__| |_| | (_) | | | | |  | | |____" +
                "\n\t /_/    \\_\\___|\\__|_|\\___/|_| |_|_|  |_|______|" +
                "\n\t" +
                "\n\t";

        logger.info(actionML);
    }

    /**
     * Print informational chart
     * @param title title of information chart
     * @param dataMap list of (key, value) pairs to print as rows
     * @param logger Logger to use for printing
     */
    public static void drawInfo(String title, List<Tuple2<String, Object>> dataMap, Logger logger) {
        final String leftAlignFormat = "║ %-30s%-28s ║";

        final String line = strMul("═", 60);

        final String preparedTitle = String.format("║ %-58s ║", title);

        final StringBuilder data = new StringBuilder();
        for (Tuple2<String, Object> t : dataMap) {
            data.append(String.format(leftAlignFormat, t._1, t._2));
            data.append("\n\t");
        }

        final  String info = "" +
                "\n\t╔" + line + "╗" +
                "\n\t"  + preparedTitle +
                "\n\t"  + data.toString().trim() +
                "\n\t╚" + line + "╝" +
                "\n\t";
        logger.info(info);
    }

    /**
     * Append n copies of str to create new String
     * @param str String to copy
     * @param n How many times to copy
     * @return String created by combining n copies of str
     */
    private static String strMul(String str, int n) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++)
            sb.append(str);
        return sb.toString();
    }

    public static class OptionCollection<T>{
        private final Optional<List<T>> collectionOpt;

        public OptionCollection(Optional<List<T>> collectionOpt) {
            this.collectionOpt = collectionOpt;
        }
        public List<T> getOrEmpty(){
            if(!collectionOpt.isPresent()){
                return new ArrayList<T>();
            }
            return collectionOpt.get();
        }
    }


    public static class IndexedDatasetConversions {
        private final IndexedDatasetJava indexedDataset;
        private transient final Logger logger = LoggerFactory.getLogger(IndexedDatasetConversions.class);


        public IndexedDatasetConversions(IndexedDatasetJava indexedDataset) {
            this.indexedDataset = indexedDataset;
        }


        public JavaPairRDD<String, java.util.Map<String,JsonAST.JValue>> toStringMapRDD(final String actionName){
            final BiDictionaryJava rowIDDictionary = indexedDataset.getRowIds();
            final SparkDistributedContext temp = (SparkDistributedContext) indexedDataset.getMatrix().context();
            final SparkContext sc = temp.sc();

            final ClassTag<BiDictionaryJava> tag = scala.reflect.ClassTag$.MODULE$.apply(BiDictionaryJava.class);
            final Broadcast<BiDictionaryJava> rowIDDictionary_bcast = sc.broadcast(rowIDDictionary,tag);

            final BiDictionaryJava columnIDDictionary = indexedDataset.getColIds();
            final Broadcast<BiDictionaryJava> columnIDDictionary_bcast = sc.broadcast(columnIDDictionary,tag);

            // may want to mapPartition and create bulk updates as a slight optimization
            // creates an RDD of (itemID, Map[correlatorName, list-of-correlator-values])
            final JavaRDD<Tuple2<Integer, Vector>> _to = ((CheckpointedDrmSpark) indexedDataset.getMatrix()).rddInput().asRowWise().toJavaRDD();
            final JavaPairRDD<Integer,Vector> to = JavaPairRDD.fromJavaRDD(_to);
            return to.mapToPair(entry -> {
                final int rowNum = entry._1();
                final Vector itemVector = entry._2();

                // turns non-zeros into list for sorting
                final List<Pair<Integer,Double>> itemList = new ArrayList<>();
                for(Vector.Element ve : itemVector.nonZeroes()) {
                    itemList.add(new Pair<Integer,Double>(ve.index(),ve.get()));
                }
                // sort by highest strength value descending(-)
                final Comparator<Pair<Integer,Double>> c =
                        (ele1,ele2) -> (new Double (ele1.getValue().doubleValue() * -1.0))
                                .compareTo(new Double(ele2.getValue().doubleValue() * -1.0));
                itemList.sort(c);
                final List<Pair<Integer,Double>> vector = itemList;

                final String invalid = "INVALID_ITEM_ID";
                final Object itemID = rowIDDictionary_bcast.value().inverse().getOrElse(rowNum,invalid);
                final String itemId = itemID.toString();
                try {
                    // equivalent to Predef.require
                    if(!itemId.equals("INVALID_ITEM_ID")){
                        throw new IllegalArgumentException("Bad row number in  matrix, skipping item "+ rowNum);
                    }

                    // equivalent to Predef.require #2
                    if(!vector.isEmpty()) {
                        throw new IllegalArgumentException("No values so skipping item " + rowNum);
                    }

                    // create a list of element ids
                    final JsonAST.JArray values = (JsonAST.JArray) (vector.stream().map(item ->
                            (JsonAST.JString) columnIDDictionary_bcast.value().inverse()
                                    .getOrElse(item.getKey(),""))); // should always be in the dictionary

                    final java.util.Map<String,JsonAST.JValue> tmp = new HashMap<>();
                    tmp.put(actionName,values);
                    //Map<String,JsonAST.JValue> rtn = JavaConverters.mapAsScalaMapConverter(tmp).asScala();

                    return new Tuple2<String, java.util.Map<String,JsonAST.JValue>>
                            (itemId, tmp);
                } catch(IllegalArgumentException e) {
                    return new Tuple2<String, java.util.Map<String,JsonAST.JValue>> (null,null);
                }

            }).filter(ele -> ele != null);
        }
    }
}