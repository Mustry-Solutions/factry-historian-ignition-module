Make changes to the design_document.md. First read it fully, then implement the changes. 
In general, change the fact that we talk about the module like it already exists. It's one that would have to be created

**Introduction:**
Make it a description of what this document actually is: a research for Factry that defines what it will take to actually build a historian module for Factry in Ignition.

**Ignition platform**
leave as is.

**What is a historian module in Ignition?**
This should be the next big topic, with the following subtopics:
 - Short explenation
 - Storing data to the historian
 - Retrieving data from the historian for dashboards
 - Technical: Historian SDK, Ignition Python functions to explain

Use the current topic 'Flow of data' to actually create most of this content, and then expand with with all the technical info that it takes to actually build the historian.


**Building the module for Factry**
- Use Factry Collector Integration topic
- Use the Supported functions topic to explain what functions we can use for the Factry module, since annotations and renaming of history is not possible right now in Factry. Remember, most of the info in these topics should already be implemented in 'What is a historian module in Ignition?', but here we want to be specific about what functions we will actually implement

- We want to be clear about the choices they have. The main choice being about whether or not to use the existing Factry collector, and let the module communicate with the collector, or having the module register itself in Factry as a collector. The advantage of the first is that less functionality will have to be deployed, since store and forward fe is already implemented in Factry's collector, and they have more control on updates to collectors then the Ignition module.
The advantage of actually having the collector inside of Ignition is that there is one less thing for customers to install and one less point of failure in the data-chain. Both options look fine, but it' really up to them what to do with this. we hope that with this info they are taking a well-informed decision. 

- The historian provider in Ignition is something that will have to be written. This provider does all the data retrieval. The most work here is converting the queries in Ignition to the right queries in Factry.

Make sure it is also clear that the historian provider will enable Ignition to also discover and query data in Factry that was not ingested by Ignition, so f.e. when customers create the asset structure in Factry, they can also use that in Ignition, even if they potentially don't use that same structure in Ignition.

- Optional is also to add extra scripting function in Ignition. F.e. the events are a big feature in Factry, and is something that is not in Ignition. We could make a system function in Ignition to also retrieve events. We suggest to wait for customers actually requesting this feature, since this goes beyond the MVP. 

**Summary of required features**
Make this part more clear 

**Proposed Milestones and estimations**
fix the way it is written now.
add something that will say we estimate the job to take 30-40 programming days, and 3 days of project management.

**Executive summary**
Make a good summary without getting lost in the technical details



