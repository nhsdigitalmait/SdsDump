/*
 Copyright 2016  Simon Farrow <simon.farrow1@hscic.gov.uk>

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package uk.nhs.digital.mait.sdsdump;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import static javax.naming.directory.SearchControls.SUBTREE_SCOPE;
import javax.naming.directory.SearchResult;

/**
 *
 * @author simonfarrow
 */
public class SdsDump {

    public static void main(String[] args) throws NamingException, IOException {
        //ldapServer = "ldap://192.168.128.11:389"; // OpenTest
        String ldapServer = "ldaps://orange.testlab.nhs.uk/"; // orange
        String outputFile = "sdsdump.xml";
        new SdsDump(ldapServer, outputFile, new String[]{"A20047", "B82617"});
    }

    /**
     * Constructor
     * @param ldapServer
     * @param outputFile
     * @param odsCodes
     * @throws NamingException
     * @throws IOException 
     */
    public SdsDump(String ldapServer, String outputFile, String[] odsCodes) throws NamingException, IOException {
        @SuppressWarnings("UseOfObsoleteCollectionType")
        Hashtable<String, Object> env = new Hashtable<>();

        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.SECURITY_AUTHENTICATION, "none");
        env.put(Context.PROVIDER_URL, ldapServer);

        // the following is helpful in debugging errors
        //env.put("com.sun.jndi.ldap.trace.ber", System.err);
        InitialDirContext ctx = new InitialDirContext(env);

        SearchControls searchControls = new SearchControls();
        searchControls.setSearchScope(SUBTREE_SCOPE);

        try (
                 FileWriter fw = new FileWriter(outputFile)) {
            fw.write("<reference type=\"sdsDump\">\r\n");

            //ahm = queryOrgByOdsCode(ctx, searchControls, odsCode);
            for (String odsCode : odsCodes) {
                ArrayList<HashMap<String, ArrayList<String>>> ahm = queryServiceByOdsCode(ctx, searchControls, odsCode);
                dumpODS(ahm, ctx, searchControls, odsCode, fw);
            }
            fw.write("</reference>\r\n");
        }
    }

    /**
     *
     * @param ctx
     * @param searchControls
     * @param odsCode
     * @throws NamingException
     */
    private ArrayList<HashMap<String, ArrayList<String>>> queryServiceByOdsCodeInteractionID(InitialDirContext ctx, SearchControls searchControls, String odsCode, String interactionId) throws NamingException {
        // services by ods code and interaction id
        return processLDAPEnumeration(ctx.search("ou=services,o=nhs", "(&(nhsIDCode=" + odsCode + ")(nhsMhsSvcIA=" + interactionId + "))", searchControls));
    }

    /**
     * @param ctx
     * @param searchControls
     * @param odsCode
     * @throws NamingException
     */
    private ArrayList<HashMap<String, ArrayList<String>>> queryServiceByOdsCode(InitialDirContext ctx, SearchControls searchControls, String odsCode) throws NamingException {
        return processLDAPEnumeration(ctx.search("ou=services, o=nhs", "(&(nhsIDCode=" + odsCode + ")(objectClass=nhsAs))", searchControls));
    }
    /**
     *
     * @param results
     * @return arraylist of single entry hashmaps containing array of values
     * @throws NamingException
     */
    private ArrayList<HashMap<String, ArrayList<String>>> processLDAPEnumeration(NamingEnumeration<SearchResult> results) throws NamingException {
        ArrayList<HashMap<String, ArrayList<String>>> al = new ArrayList<>();
        while (results.hasMore()) {
            SearchResult result = results.next();
            Attributes attributes = result.getAttributes();
            NamingEnumeration<String> ids = attributes.getIDs();
            while (ids.hasMore()) {
                HashMap<String, ArrayList<String>> hm = new HashMap<>();
                ArrayList<String> al1 = new ArrayList<>();
                Attribute attribute = attributes.get(ids.next());
                NamingEnumeration<?> iter = attribute.getAll();
                while (iter.hasMore()) {
                    Object value = iter.next();
                    System.out.println(attribute.getID() + " : " + value);
                    al1.add(value.toString());
                }
                hm.put(attribute.getID(), al1);
                al.add(hm);
            }
            System.out.println();
        }
        return al;
    }

    /**
     * appends results for ODS code to output file
     *
     * @param ahm array of hashmaps of raw results from sds
     * @param ctx ldap context
     * @param searchControls jmdi search controls
     * @param odsCode
     * @param fw FileWriter object
     * @throws IOException
     * @throws NamingException
     */
    private void dumpODS(ArrayList<HashMap<String, ArrayList<String>>> ahm, InitialDirContext ctx, SearchControls searchControls, String odsCode, FileWriter fw) throws IOException, NamingException {

        // reorder an array of hashmaps intoi something more tractable
        HashMap<String, ArrayList<String>> deviceResults = new HashMap<>();
        for (HashMap<String, ArrayList<String>> hm : ahm) {
            for (String key : hm.keySet()) {
                deviceResults.put(key, hm.get(key));
            }
        }

        // returns party key, asid, list of interactions
        String asid = deviceResults.get("uniqueIdentifier").get(0);
        ArrayList<String> interactions = deviceResults.get("nhsAsSvcIA");

        // iterate through the interactions
        for (String interactionId : interactions) {
            ahm = queryServiceByOdsCodeInteractionID(ctx, searchControls, odsCode, interactionId);

            // remap into a cooked state from the raw state
            HashMap<String, ArrayList<String>> results = new HashMap<>();
            for (HashMap<String, ArrayList<String>> hm : ahm) {
                for (String key : hm.keySet()) {
                    results.put(key, hm.get(key));
                }
            }

            fw.write("<entry ");
            fw.write("asid=\"" + asid + "\" ");
            fw.write("nacs=\"" + results.get("nhsIDCode").get(0) + "\" ");
            fw.write("cpaid=\"" + results.get("nhsMhsCPAId").get(0) + "\" ");
            fw.write("service=\"" + results.get("nhsMHsSN").get(0) + "\" ");
            fw.write("interaction=\"" + "" + "\" ");
            fw.write("partykey=\"" + results.get("nhsMHSPartyKey").get(0) + "\" ");
            fw.write("mhsactor=\"" + "urn:oasis:names:tc:ebxml-msg:actor:toPartyMSH" + "\" ");
            fw.write("sycReply=\"" + results.get("nhsMHSSyncReplyMode").get(0) + "\" ");
            fw.write("dupElim=\"" + results.get("nhsMHSDuplicateElimination").get(0) + "\" ");
            fw.write("ackRq=\"" + results.get("nhsMHSAckRequested").get(0) + "\" ");
            fw.write("soapaction=\"" + results.get("nhsMhsSvcIA").get(0) + "\" ");
            fw.write("endpoint=\"" + results.get("nhsMHSEndPoint").get(0) + "\" ");
            fw.write(" />\r\n");
        }
    }
}
