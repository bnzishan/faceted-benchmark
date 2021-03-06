package org.hobbit.evaluation;

import org.apache.commons.io.Charsets;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFactory;
import org.apache.jena.rdf.model.Model;
import org.hobbit.core.components.AbstractEvaluationModule;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Created on 9/12/2016.
 */
public class EvaluationModule extends AbstractEvaluationModule {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvaluationModule.class);

    private int number_of_queries;
    private InstancesEvalHelper evalOverall;
    private HashMap<QueryID, InstancesEvalHelper> evalCPTs;
    private ArrayList<QueryID> queriesWithTimeout;

    private int tp;
    private int fn;
    private int fp;

    private int number_of_counts;
    private int count_error;
    private double count_error_ratio;
    private long sum_of_correct_count_results;
    private int count_time_needed;

    private int timeOut;


    @Override
    public void init() throws Exception {
        super.init();

        evalOverall = new InstancesEvalHelper(0,0,0,0);
        evalCPTs = new HashMap<>();

        number_of_queries = 0 ;

        number_of_counts = 0;
        count_error = 0;
        count_error_ratio =0;
        sum_of_correct_count_results =0L;
        count_time_needed = 0;
        queriesWithTimeout = new ArrayList<>();

        timeOut = 60000; // max time to answer a query in ms
    }

    @Override
    protected void evaluateResponse(byte[] expectedData, byte[] receivedData, long taskSentTimestamp,
                                    long responseReceivedTimestamp) throws Exception {


        /* receivedData SHOULD NOT STILL CONTAIN TASKID? IF IT DOES CHANGE BACK TO:
        ByteBuffer bufferRec = ByteBuffer.wrap(receivedData);
        String taskidRec = RabbitMQUtils.readString(bufferRec);
        String resultsString = RabbitMQUtils.readString(bufferRec);

        */
        LOGGER.info("Evaluating response...");

        // change to sending around JSON.
        //
        // OLD:
        // String resultsString = RabbitMQUtils.readString(receivedData);

        // NEW:
        InputStream inReceived = new ByteArrayInputStream(receivedData);

        String resultsString;
        try {
            ResultSet received = ResultSetFactory.fromJSON(inReceived);
            byte[] resultsBytes = formatResultData(received);
            resultsString = RabbitMQUtils.readString(resultsBytes);
        } catch ( org.apache.jena.atlas.json.JsonParseException e){
            resultsString="";
        }


        // LOGGER.info("resultsString: "+ resultsString);
        /*DOES GOLD STILL CONTAIN TASKID?
        YES, BY OUR OWN DEF IN TASK GENERATOR. SHOULD PROBABLY TAKE OUT TASKID OUT OF ADJUSTFORMAT METHOD IN  TASK GENERATOR
        IF CHANGED, JUST DO:
        ByteBuffer bufferExp = ByteBuffer.wrap(expectedData);
        String scenario = RabbitMQUtils.readString(bufferExp);
        String query = RabbitMQUtils.readString(bufferExp);
        String goldsString = RabbitMQUtils.readString(bufferExp);

         */
        ByteBuffer bufferExp = ByteBuffer.wrap(expectedData);
        String taskidGold = RabbitMQUtils.readString(bufferExp);
        LOGGER.info("Eval_mod task Id: "+ taskidGold);
        String scenario = RabbitMQUtils.readString(bufferExp);
        LOGGER.info("Scenario id: "+ scenario);
        String query = RabbitMQUtils.readString(bufferExp);
        LOGGER.info("query: "+ query);

        String goldsString = RabbitMQUtils.readString(bufferExp);
        // LOGGER.info("goldsString: "+ goldsString);

        TimeUnit.MILLISECONDS.sleep(500);
        String[] resultsArray = resultsString.split(",");
        for (int i=0 ; i< resultsArray.length; i++){
            resultsArray[i]=resultsArray[i].trim();
        }

        String[] goldsArray = goldsString.split(",");
        for (int i=0 ; i< goldsArray.length; i++){
            goldsArray[i]=goldsArray[i].trim();
        }

        ArrayList<String> receivedDataInstances = new ArrayList<>(Arrays.asList(resultsArray));
        ArrayList<String> expectedDataInstances = new ArrayList<>(Arrays.asList(goldsArray));

        ArrayList<String> empties = new ArrayList<>();
        empties.add("");
        receivedDataInstances.removeAll(empties);
        expectedDataInstances.removeAll(empties);
        QueryID key = new QueryID(Integer.parseInt(scenario), Integer.parseInt(query));

        if (!scenario.contains("0") || (scenario.contains("10"))) {
            number_of_queries+=1;
            // Evaluate the given response pair.

            tp = 0;
            fn = 0;
            for (String correct_instance : expectedDataInstances) {
                if (receivedDataInstances.contains(correct_instance)) {
                    tp += 1;
                } else {
                    fn += 1;
                }
            }
            fp = receivedDataInstances.size() - tp;

            int time_needed = responseReceivedTimestamp > 0L && responseReceivedTimestamp - taskSentTimestamp < timeOut ?
                    (int) (responseReceivedTimestamp - taskSentTimestamp) : timeOut;

            if(responseReceivedTimestamp == 0L){
                queriesWithTimeout.add(new QueryID(Integer.parseInt(scenario),Integer.parseInt(query)));
            }

            if(responseReceivedTimestamp-taskSentTimestamp < 0L){
                LOGGER.error("RESPONSE TIME BEFORE TASK WAS SENT. RESPONSE TIME: "+responseReceivedTimestamp+"TASK SENT TIME: "+taskSentTimestamp );
            }


            evalCPTs.put(key, new InstancesEvalHelper(tp, fn, fp, time_needed));

            evalOverall.add(tp, fn, fp, time_needed);

        }
        else {
            LOGGER.info("Scenario id is zero!");
            Integer receivedCount;
            try {
                receivedCount = Integer.parseInt((receivedDataInstances.get(0).split("\\^")[0]));
                //receivedCount = Integer.parseInt(receivedDataInstances.get(0));
            } catch (NumberFormatException e) {
                receivedCount = 0;
            }
            Integer expectedCount;
            try {
                expectedCount = Integer.parseInt((expectedDataInstances.get(0).split("\\^")[0]));
            } catch (NumberFormatException e) {
                expectedCount = 0;
            }
            //Integer expectedCount = Integer.parseInt(expectedDataInstances.get(0));

            number_of_counts += 1;

            count_time_needed += responseReceivedTimestamp > 0L  && responseReceivedTimestamp - taskSentTimestamp < timeOut ?
                    (int) (responseReceivedTimestamp - taskSentTimestamp) : timeOut;

            if(responseReceivedTimestamp == 0L){
                queriesWithTimeout.add(new QueryID(Integer.parseInt(scenario),Integer.parseInt(query)));
            }

            int current_error = Math.abs(receivedCount - expectedCount);

            count_error += current_error;
            count_error_ratio += (double) current_error / Math.max(expectedCount,1);

            sum_of_correct_count_results += expectedCount;
        }
    }




    @Override
    protected Model summarizeEvaluation ()throws Exception {

        // ______________________________________
        // 1.a) Overall results
        // ______________________________________

        double overall_query_per_second_score;
        double overall_precision;
        double overall_recall;
        double overall_f1;
        // ______________________________________
        // 1.a.i) Time score in queries per second
        // ______________________________________

        overall_query_per_second_score = (double)  1000*number_of_queries / evalOverall.getTime();

        // ______________________________________
        // 1.a.ii) Precision, recall, F1-measure
        // ______________________________________


        // Compute precision, recall and f1-measure

        overall_precision = (evalOverall.getTP()==0 && evalOverall.getFP()==0) ? 0.0 :
                (double) evalOverall.getTP() / (evalOverall.getTP() + evalOverall.getFP());
        overall_recall = (evalOverall.getTP()==0 && evalOverall.getFN()==0) ? 0.0 :
                (double) evalOverall.getTP() / (evalOverall.getTP() + evalOverall.getFN());
        overall_f1 =(overall_recall ==0 && overall_precision==0) ? 0.0 : 2 * overall_precision * overall_recall / (overall_precision + overall_recall);


        // ______________________________________
        // 1.b) Results per choke point
        // ______________________________________

        HashMap<Integer, Double> chokePT_query_per_second_score = new HashMap<>();
        HashMap<Integer, Double> chokePT_precision = new HashMap<>();
        HashMap<Integer, Double> chokePT_recall = new HashMap<>();
        HashMap<Integer, Double> chokePT_f1 = new HashMap<>();

        HashMap<Integer, ArrayList<QueryID>> chokePointsTable = ChokePoints.getTable();

        // ______________________________________
        // 1.b.i) Time score in queries per second
        // ______________________________________

        // For each choke point, find the queries related to it with help of the choke points table
        // and sum the time needed over all those queries
        // Then for each choke point return the query-per-seconds score as in 1.a.i

        // loop over choke points
        Set<Map.Entry<Integer, ArrayList<QueryID>>> chokePT_entries = chokePointsTable.entrySet();

        for (Map.Entry<Integer, ArrayList<QueryID>> chokePt_entry : chokePT_entries) {
            Integer key = chokePt_entry.getKey();
            List<QueryID> queries = chokePt_entry.getValue();

            int current_chokePT_number_of_queries = 0;
            InstancesEvalHelper currentChokePT_eval = new InstancesEvalHelper(0,0,0,0);

            for (QueryID query : queries) {
                try {
                    currentChokePT_eval.add(evalCPTs.get(query));
                    current_chokePT_number_of_queries += 1;
                } catch(NullPointerException e){
                    currentChokePT_eval.add(0,0,0,0);
                    LOGGER.info("Query selection went wrong.");
                }
            }


            double current_chokePT_query_per_second_score = (double) 1000 * current_chokePT_number_of_queries / currentChokePT_eval.getTime();
            double current_chokePT_precision = (currentChokePT_eval.getTP()==0 && currentChokePT_eval.getFP()==0) ? 0 :
                    (double) currentChokePT_eval.getTP() / (currentChokePT_eval.getTP() + currentChokePT_eval.getFP());
            double current_chokePT_recall = (currentChokePT_eval.getTP()==0 && currentChokePT_eval.getFN()==0) ? 0 :
                    (double) currentChokePT_eval.getTP() / (currentChokePT_eval.getTP() + currentChokePT_eval.getFN());
            double current_chokePT_f1 = (current_chokePT_recall==0 && current_chokePT_precision==0) ? 0 :
                    2 * current_chokePT_precision * current_chokePT_recall / (current_chokePT_precision + current_chokePT_recall);

            chokePT_query_per_second_score.put(key, current_chokePT_query_per_second_score);
            chokePT_precision.put(key, current_chokePT_precision);
            chokePT_recall.put(key, current_chokePT_recall);
            chokePT_f1.put(key, current_chokePT_f1);

        }

        // ______________________________________
        // 2.) COUNT queries
        // ______________________________________


        // ______________________________________
        // 2.a.i) Time score in queries per second
        // ______________________________________


        double count_per_second_score = count_time_needed!=0? (double) 1000 * number_of_counts / count_time_needed : 0.0;

        // ______________________________________
        // 2.a.ii) Correctness of counts
        // ______________________________________

        long overall_error = count_error;
        double average_error = number_of_counts!=0 ? (double) count_error/number_of_counts : -1.0;
        double overall_error_ratio =sum_of_correct_count_results!=0 ? (double)  count_error/sum_of_correct_count_results : -1.0;
        double average_error_ratio =  number_of_counts !=0 ? count_error_ratio/number_of_counts : -1.0;


        // create a rdf model which will hold the results


        return ReturnModelBuilder.build(experimentUri,
                overall_query_per_second_score,
                overall_precision,
                overall_recall,
                overall_f1,
                chokePT_query_per_second_score,
                chokePT_precision,
                chokePT_recall,
                chokePT_f1,
                overall_error,
                average_error,
                overall_error_ratio,
                average_error_ratio,
                count_per_second_score,
                queriesWithTimeout);

    }

    private byte[] formatResultData(ResultSet result){
        StringBuilder listString = new StringBuilder();
        while(result.hasNext()) {
            String value = (result.next().get(result.getResultVars().get(0)).toString());
            listString.append(value+",");
        }
        byte[] resultsByteArray = listString.toString().getBytes(Charsets.UTF_8);
        return resultsByteArray;
    }

}



