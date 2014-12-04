// yes, I am in a time bubble and don't know any better libraries than jquery
$(function() {
    var shellInputContainerSel = '#shellInputContainer';
    var shellInputSel = '#shellInput';
    var resultsSel = "#results";
    var serverUrl = "/workbench/command.json";
    var shellPrompt = ">> ";
    var localCommands = {
       "clear": function() {
           $(resultsSel).html("");
       },
       "help": function() {
           var helpStr = ["","help: shows this output", 
                          "clear: clears the console", 
                          "select: run sql commands",
                          "histogram &lt;tableName&gt;, &lt;columnName&gt;: Plots the histogram",
                         "desc [tableName]: if tableName is given gets the column names and type for this table or gets all available tables",
                         "aliases: lists all aliases, to use an alias type cmdName parameters e.g to run top10, use top10 commits,author",
                         "alias command=sql: aliases the sql with a command can be parameterized, run aliases for samples",
                         "graph dataset: plots a force directed graph (flowery thingy) for a dataset, the dataset should have been registered before via the spark-shell"].join("<br>");
           $(resultsSel).append(helpStr);
       },
       "aliases": function() {
           var aliasListStr = "";
           _.each(aliases, function(value, key) {aliasListStr+= "<br> $1 : $2".format(key, value);});
           $(resultsSel).append(aliasListStr);
       },
       "alias":function(command) {
           var aliasRegex = /alias (.+)=(.+)/;
           var splits = aliasRegex.exec(command);
           addAlias(splits[1], splits[2]);
       }
    };
    var prettyPrinters = {};
    var commandStack = [];
    var currHistoryCursor = 0;
    var commandNumber = 0;
    var commandResults = [];
    var plotter = new Plotter();
    var aliases = {};
    var cmdRegex = /\s*(\S+)\s+(.+)/;

    if (typeof String.prototype.format !== 'function') {
        String.prototype.format = function() {
            var formatted = this, i, vals;
            if ($.isArray(arguments[0])) {
                vals = arguments[0];
            } else {
                vals = arguments;
            }
            for (i = 0; i < vals.length; i++) {
                formatted = formatted.replace(new RegExp("\\$" + (i + 1),"g"), vals[i]);
            }
            return formatted;
        };
    }

    $(shellInputSel).keyup(function(event){
        var command = $(shellInputSel).val();
	    var keycode = (event.keyCode ? event.keyCode : event.which);
	    if(keycode == '13'){
            $(resultsSel).append("<br>" + shellPrompt + command);
            $(shellInputContainerSel).hide();
            runCommand(command);
            currHistoryCursor = commandStack.length;
	    } else if (keycode == '40' && event.ctrlKey) {
            setInputCmdAs(getNextCommand());//down
        } else if (keycode == '38' && event.ctrlKey) {
            setInputCmdAs(getPrevCommand());//up
        }
    });

    function runCommand(command) {
        var cmdSplits = cmdRegex.exec(command);
        if (cmdSplits === null) {
            cmdSplits = ["",command];
        }
        commandStack.push(command);
        if (localCommands[cmdSplits[1]]) {
            setInputCmdAs();
            localCommands[cmdSplits[1]].call(this, command);
        } else {
            if (aliases[cmdSplits[1]]) {
                command = aliases[cmdSplits[1]].format((cmdSplits[2]||"").split(","));
            }
            runServerCommand(command);
        }
    }

    function runServerCommand(commandStr) {
		var resultsStr = "";
        var commandHolder = { id:commandNumber};
        var cmdParser = /^(desc|histogram|graph)\s*(.*)/;
        var parsedResults;
        var cmdType, cmdArgs;
        cmdType = "query";
        cmdArgs = commandStr;
        var resultContainerId = "resultContainer"+commandNumber;

        if ((parsedResults = cmdParser.exec(commandStr)) && (parsedResults.length > 2)) {
            cmdType = parsedResults[1];
            cmdArgs = parsedResults[2];
        }
		resultsStr = "<div class='cmdResultContainer' id='"+resultContainerId+"'></div>";
		$(resultsSel).append(resultsStr);

        $.ajax({
            url: serverUrl,
            type: "POST",
            data : "payload="+JSON.stringify({"command":cmdType, "args": cmdArgs})
        }).done(function(data) {
            var payload = data.success;
            if (payload === false) {
			    $("#"+resultContainerId).html("<br>Could not execute query");
            } else {
                commandHolder.result = data;
                prettyPrinters[cmdType].call(this,data, resultContainerId);
            }
            commandResults.push(commandHolder);
            commandNumber++;
            setInputCmdAs();
        }).error(function(data) {
            setCmdResult(resultContainerId,"<br>Could not execute query, check the syntax of the query, remove semi comlons if used at end of query");
            setInputCmdAs();
        });
    }

    function getNextCommand() {
        if (currHistoryCursor < commandStack.length - 1) {
            currHistoryCursor++;
            return commandStack[currHistoryCursor];
        } else {
            return "";
        }
    }

    function getPrevCommand() {
        if (currHistoryCursor > 0) {
            currHistoryCursor--;
            return commandStack[currHistoryCursor];
        } else {
            return "";
        }
    }

    function setInputCmdAs(command) {
        var initialCommand = command || "";
        $(shellInputSel).val(initialCommand);
        $(shellInputContainerSel).show();
        $(shellInputSel).focus();
    }

    function setCmdResult(containerId, contents) {
        $("#"+containerId).html(contents);
    }

    function addAlias(commandName, commandValue) {
        aliases[commandName] = commandValue;
    }

    addAlias("top10","select $2, count(*) as fieldCount from $1 group by $2 order by fieldCount desc limit 10");

    addAlias("top20","select $2, count(*) as fieldCount from $1 group by $2 order by fieldCount desc limit 20");

    addAlias("count","select count(*) from $1");

    addAlias("few","select * from $1");

    prettyPrinters.query = function(data, containerId) {
        var resultsStr = "";
        if (data.length > 0) {
            resultsStr +="<table class='table table-bordered table-results'>";
            $.each(data, function(){
				var trStr = "<tr>";
				$.each(this, function(k, v){
					trStr += "<td>"+v+"</td>";
				});
				resultsStr += trStr;
			}); //end of each block
            resultsStr += "</table>";
        } else {
            resultsStr = "<br>No rows returned for this query";
        }
        
        setCmdResult(containerId,resultsStr);
    };

    prettyPrinters.desc = function(data, containerId) {
       return  prettyPrinters.query.call(this, _.map(data, function(val){ return (val._1)?  [val._2, val._1] : [val];}), containerId);
    };

    prettyPrinters.histogram = function(data, containerId) {
        plotter.plotHistogram("#"+containerId, data.stats, data.histogram, data.metadata);
    };

    prettyPrinters.graph = function(data, containerId) {
        var selector, graph;
        selector = "#"+containerId;
        $(selector).append('<div class="search-graph-ops"><button type="button" class="btn btn-small zoom-in" title="Zoom-In"><span class="icon icon-zoom-in zoom-in" /></button> <button type="button" class="btn btn-small" title="Zoom-Out"><span class="icon icon-zoom-out" /></button> <button type="button" class="btn btn-small" title="Rectangle-select"><span class="icon icon-pencil" /></button> <input type="text" placeholder="Search Vertices"/></div>');
        graph = plotter.graph(selector, data);
        addSearch($(selector +" .search-graph-ops input"), graph);
        addZoom($(selector +" .search-graph-ops button:not(:last)"), graph);
        addStats($(selector +" .search-graph-ops"), graph)
    };


    function addSearch(elSelector, graph) {
        elSelector.keypress(function(event) {
            var keycode = (event.keyCode ? event.keyCode : event.which);
	        if(keycode == '13'){
		        graph.search(elSelector.val());
	        }
        });
    }

    function addZoom(elSelector, graph) {
        elSelector.click(function(event) {
            var zoomIn = $(event.target).hasClass("zoom-in");
            graph.zoom(zoomIn);
        });
    }

    function addStats(elSelector, graph) {
        
    }
});
