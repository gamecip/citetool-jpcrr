#!/usr/bin/env lua
----------------------------------------------------------------------------------------------------------------------
----------------------------------------------------------------------------------------------------------------------
-- NHMLFixup v12 by Ilari (2012-04-09).
-- Update timecodes in NHML Audio/Video track timing to conform to given MKV v2 timecodes file.
-- Syntax: NHMLFixup <video-nhml-file> <audio-nhml-file> <mkv-timecodes-file> [delay=<delay>] [tvaspect|widescreen]
--
-- <delay> is number of milliseconds to delay the video (in order to compensate for audio codec delay, reportedly
-- does not work right with some demuxers).
-- The 'tvaspect' option makes video track to be automatically adjusted to '4:3' aspect ratio.
-- The 'widescreen' option makes video track to be automatically adjusted to '16:9' aspect ratio.
-- <frame> is frame to force overflow correction on.
--
-- Version v12 by Ilari (2012-04-09):
--	- Complain about CTSOffset errors.
--
-- Version v11 by Ilari (2011-12-18):
--	- Add option to force overflow correction on given frame.
--
-- Version v10 by Ilari (2011-08-16):
--	- Work around MP4Box bug resulting in negative CTSOffset.
--
-- Version v9 by Ilari (2011-04-01):
--	- Support widescreen mode ("widescreen").
--
-- Version v8 by Ilari (2010-12-06):
--	- Support Special timecode file "@CFR" that fixes up audio for CFR encode.
--
-- Version v7 by Ilari (2010-10-24):
--	- Fix bug in time division (use integer timestamps, not decimal ones).
--
-- Version v6 by Ilari (2010-10-24):
--	- Make it work on Lua 5.2 (work 4).
--
-- Version v5 by Ilari (2010-09-18):
--	- Move the files first out of way, since rename-over fails on Windows.
--
-- Version v4 by Ilari (2010-09-17):
--	- Change audio track ID if it collides with video track..
--
-- Version v3 by Ilari (2010-09-17):
--	- Support setting aspect ratio correction.
--
-- Version v2 by Ilari (2010-09-16):
--	- If sound and video NHMLs are wrong way around, automatically swap them.
--	- Check that one of the NHMLs is sound and other is video.
--
----------------------------------------------------------------------------------------------------------------------
----------------------------------------------------------------------------------------------------------------------

--Lua 5.2 fix:
unpack = unpack or table.unpack;

----------------------------------------------------------------------------------------------------------------------
-- Function reduce_fraction(number numerator, number denumerator)
-- Returns reduced fraction.
----------------------------------------------------------------------------------------------------------------------
reduce_fraction = function(numerator, denumerator)
	local x, y = numerator, denumerator;
	while y > 0 do
		x, y = y, x % y;
	end
	return numerator / x, denumerator / x;
end

----------------------------------------------------------------------------------------------------------------------
-- Function load_timecode_file(FILE file, number timescale)
-- Loads timecode data from file @file, using timescale of @timescale frames per second. Returns array of scaled
-- timecodes.
----------------------------------------------------------------------------------------------------------------------
load_timecode_file = function(file, timescale)
	local line, ret;
	line = file:read("*l");
	if line ~= "# timecode format v2" then
		error("Timecode file is not in MKV timecodes v2 format");
	end
	ret = {};
	while true do
		line = file:read("*l");
		if not line then
			break;
		end
		local timecode = tonumber(line);
		if not timecode then
			error("Can't parse timecode '" .. line .. "'.");
		end
		table.insert(ret, math.floor(0.5 + timecode / 1000 * timescale));
	end
	return ret;
end

----------------------------------------------------------------------------------------------------------------------
-- Function make_reverse_index_table(Array array)
-- Returns table, that has entry for each entry in given array @array with value being rank of value, 1 being smallest
-- and #array the largest. If @lookup is non-nil, values are looked up from that array.
----------------------------------------------------------------------------------------------------------------------
make_reverse_index_table = function(array, lookup)
	local sorted, ret;
	local i;
	sorted = {};
	for i = 1,#array do
		sorted[i] = array[i];
	end
	table.sort(sorted);
	ret = {};
	for i = 1,#sorted do
		ret[sorted[i]] = (lookup and lookup[i]) or i;
	end
	return ret;
end

----------------------------------------------------------------------------------------------------------------------
-- Function max_causality_violaton(Array CTS, Array DTS)
-- Return the maximum number of time units CTS and DTS values violate causality. #CTS must equal #DTS.
----------------------------------------------------------------------------------------------------------------------
max_causality_violation = function(CTS, DTS)
	local max_cv = 0;
	local i;
	for i = 1,#CTS do
		max_cv = math.max(max_cv, DTS[i] - CTS[i]);
	end
	return max_cv;
end

----------------------------------------------------------------------------------------------------------------------
-- Function fixup_video_times(Array sampledata, Array timecodes, Number spec_delay)
-- Fixes video timing of @sampledata (fields CTS and DTS) to be consistent with timecodes in @timecodes. Returns the
-- CTS offset of first sample (for fixing audio). @spec_delay is special delay to add (to fix A/V sync).
----------------------------------------------------------------------------------------------------------------------
fixup_video_times = function(sampledata, timecodes, spec_delay)
	local cts_tab = {};
	local dts_tab = {};
	local k, v, i;

	if not timecodes then
		local min_cts = 999999999999999999999;
		for i = 1,#sampledata do
			--Maximum causality violation is always zero in valid HHML.
			sampledata[i].CTS = sampledata[i].CTS + spec_delay;
			--Spec_delay should not apply to audio.
			min_cts = math.min(min_cts, sampledata[i].CTS - spec_delay);
		end
		return min_cts;
	end

	if #sampledata ~= #timecodes then
		error("Number of samples (" .. #sampledata .. ") does not match number of timecodes (" .. #timecodes
			.. ").");
	end
	for i = 1,#sampledata do
		cts_tab[i] = sampledata[i].CTS;
		dts_tab[i] = sampledata[i].DTS;
	end
	cts_lookup = make_reverse_index_table(cts_tab, timecodes);
	dts_lookup = make_reverse_index_table(dts_tab, timecodes);

	-- Perform time translation and find max causality violation.
	local max_cv = 0;
	for i = 1,#sampledata do
		sampledata[i].CTS = cts_lookup[sampledata[i].CTS];
		sampledata[i].DTS = dts_lookup[sampledata[i].DTS];
		max_cv = math.max(max_cv, sampledata[i].DTS - sampledata[i].CTS);
	end
	-- Add maximum causality violation to CTS to eliminate the causality violations.
	-- Also find the minimum CTS.
	local min_cts = 999999999999999999999;
	for i = 1,#sampledata do
		sampledata[i].CTS = sampledata[i].CTS + max_cv + spec_delay;
		--Spec_delay should not apply to audio.
		min_cts = math.min(min_cts, sampledata[i].CTS - spec_delay);
	end
	return min_cts;
end

----------------------------------------------------------------------------------------------------------------------
-- Function fixup_video_times(Array sampledata, Number min_video_cts, Number video_timescale, Number audio_timescale)
-- Fixes video timing of @sampledata (field CTS) to be consistent with video minimum CTS of @cts. Video timescale
-- is assumed to be @video_timescale and audio timescale @audio_timescale.
----------------------------------------------------------------------------------------------------------------------
fixup_audio_times = function(sampledata, min_video_cts, video_timescale, audio_timescale)
	local fixup = math.floor(0.5 + min_video_cts * audio_timescale / video_timescale);
	for i = 1,#sampledata do
		sampledata[i].CTS = sampledata[i].CTS + fixup;
	end
end

----------------------------------------------------------------------------------------------------------------------
-- Function translate_NHML_TS_in(Array sampledata);
-- Translate NHML CTSOffset fields in @sampledata into CTS fields.
----------------------------------------------------------------------------------------------------------------------
translate_NHML_TS_in = function(sampledata, default_dDTS)
	local i;
	local dts = 0;
	for i = 1,#sampledata do
		if not sampledata[i].DTS then
			sampledata[i].DTS = dts + default_dDTS;
		end
		dts = sampledata[i].DTS;
		if (sampledata[i].CTSOffset and sampledata[i].CTSOffset < 0) then
			error("Invalid CTSOffset " .. tostring(sampledata[i].CTSOffset));
		end
		if sampledata[i].CTSOffset then
			sampledata[i].CTS = sampledata[i].CTSOffset + sampledata[i].DTS;
		else
			sampledata[i].CTS = sampledata[i].DTS;
		end
	end
end

----------------------------------------------------------------------------------------------------------------------
-- Function translate_NHML_TS_out(Array sampledata);
-- Translate CTS fields in @sampledata into NHML CTSOffset fields.
----------------------------------------------------------------------------------------------------------------------
translate_NHML_TS_out = function(sampledata)
	local i;
	for i = 1,#sampledata do
		sampledata[i].CTSOffset = sampledata[i].CTS - sampledata[i].DTS;
		if sampledata[i].CTSOffset < 0 then
			error("INTERNAL ERROR: translate_NHML_TS_out: Causality violation: CTS=" .. tostring(
				sampledata[i].CTS) .. " DTS=" .. tostring(sampledata[i].DTS) .. ".");
		end
		sampledata[i].CTS = nil;
	end
end

----------------------------------------------------------------------------------------------------------------------
-- Function map_table_to_number(Table tab);
-- Translate all numeric fields in table @tab into numbers.
----------------------------------------------------------------------------------------------------------------------
map_table_to_number = function(tab)
	local k, v;
	for k, v in pairs(tab) do
		local n = tonumber(v);
		if n then
			tab[k] = n;
		end
	end
end

----------------------------------------------------------------------------------------------------------------------
-- Function map_fields_to_number(Array sampledata);
-- Translate all numeric fields in array @sampledata into numbers.
----------------------------------------------------------------------------------------------------------------------
map_fields_to_number = function(sampledata)
	local i;
	for i = 1,#sampledata do
		map_table_to_number(sampledata[i]);
	end
end

----------------------------------------------------------------------------------------------------------------------
-- Function escape_xml_text(String str)
-- Return XML escaping of text str.
----------------------------------------------------------------------------------------------------------------------
escape_xml_text = function(str)
	str = string.gsub(str, "&", "&amp;");
	str = string.gsub(str, "<", "&lt;");
	str = string.gsub(str, ">", "&gt;");
	str = string.gsub(str, "\"", "&quot;");
	str = string.gsub(str, "\'", "&apos;");
	return str;
end

----------------------------------------------------------------------------------------------------------------------
-- Function escape_xml_text(String str)
-- Return XML unescaping of text str.
----------------------------------------------------------------------------------------------------------------------
unescape_xml_text = function(str)
	str = string.gsub(str, "&apos;", "\'");
	str = string.gsub(str, "&quot;", "\"");
	str = string.gsub(str, "&gt;", ">");
	str = string.gsub(str, "&lt;", "<");
	str = string.gsub(str, "&amp;", "&");
	return str;
end

----------------------------------------------------------------------------------------------------------------------
-- Function serialize_table_to_xml_entity(File file, String tag, Table data, bool noclose);
-- Write @data as XML start tag of type @tag into @file. If noclose is true, then tag will not be closed.
----------------------------------------------------------------------------------------------------------------------
serialize_table_to_xml_entity = function(file, tag, data, noclose)
	local k, v;
	file:write("<" .. tag .. " ");
	for k, v in pairs(data) do
		file:write(k .. "=\"" .. escape_xml_text(tostring(v)) .. "\" ");
	end
	if noclose then
		file:write(">\n");
	else
		file:write("/>\n");
	end
end

----------------------------------------------------------------------------------------------------------------------
-- Function serialize_array_to_xml_entity(File file, String tag, Array data);
-- Write each element of @data as empty XML tag of type @tag into @file.
----------------------------------------------------------------------------------------------------------------------
serialize_array_to_xml_entity = function(file, tag, data)
	local i;
	for i = 1,#data do
		serialize_table_to_xml_entity(file, tag, data[i]);
	end
end

----------------------------------------------------------------------------------------------------------------------
-- Function write_NHML_data(File file, Table header, Table sampledata)
-- Write entiere NHML file.
----------------------------------------------------------------------------------------------------------------------
write_NHML_data = function(file, header, sampledata)
	file:write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");
	serialize_table_to_xml_entity(file, "NHNTStream", header, true);
	serialize_array_to_xml_entity(file, "NHNTSample", sampledata);
	file:write("</NHNTStream>\n");
end

----------------------------------------------------------------------------------------------------------------------
-- Function open_file_checked(String file, String mode, bool for_write)
-- Return file handle to file (checking that open succeeds).
----------------------------------------------------------------------------------------------------------------------
open_file_checked = function(file, mode, for_write)
	local a, b;
	a, b = io.open(file, mode);
	if not a then
		error("Can't open '" .. file .. "': " .. b);
	end
	return a;
end

----------------------------------------------------------------------------------------------------------------------
-- Function call_with_file(fun, string file, String mode, ...);
-- Call fun with opened file handle to @file (in mode @mode) as first parameter.
----------------------------------------------------------------------------------------------------------------------
call_with_file = function(fun, file, mode, ...)
	-- FIXME: Handle nils returned from function.
	local handle = open_file_checked(file, mode);
	local ret = {fun(handle, ...)};
	handle:close();
	return unpack(ret);
end

----------------------------------------------------------------------------------------------------------------------
-- Function xml_parse_tag(String line);
-- Returns the xml tag type for @line plus table of attributes.
----------------------------------------------------------------------------------------------------------------------
xml_parse_tag = function(line)
	-- More regexping...
	local tagname;
	local attr = {};
	tagname = string.match(line, "<(%S+).*>");
	if not tagname then
		error("'" .. line .. "': Parse error.");
	end
	local k, v;
	for k, v in string.gmatch(line, "([^ =]+)=\"([^\"]*)\"") do
		attr[k] = unescape_xml_text(v);
	end
	return tagname, attr;
end

----------------------------------------------------------------------------------------------------------------------
-- Function load_NHML(File file)
-- Loads NHML file @file. Returns header table and samples array.
----------------------------------------------------------------------------------------------------------------------
load_NHML = function(file)
	-- Let's regexp this shit...
	local header = {};
	local samples = {};
	while true do
		local line = file:read();
		if not line then
			error("Unexpected end of NHML file.");
		end
		local xtag, attributes;
		xtag, attributes = xml_parse_tag(line);
		if xtag == "NHNTStream" then
			header = attributes;
		elseif xtag == "NHNTSample" then
			table.insert(samples, attributes);
		elseif xtag == "/NHNTStream" then
			break;
		elseif xtag == "?xml" then
		else
			print("WARNING: Unrecognized tag '" .. xtag .. "'.");
		end
	end
	return header, samples;
end

----------------------------------------------------------------------------------------------------------------------
-- Function reame_errcheck(String old, String new)
-- Rename old to new. With error checking.
----------------------------------------------------------------------------------------------------------------------
rename_errcheck = function(old, new, backup)
	local a, b;
	os.remove(backup);
	a, b = os.rename(new, backup);
	if not a then
		error("Can't rename '" .. new .. "' -> '" .. backup .. "': " .. b);
	end
	a, b = os.rename(old, new);
	if not a then
		error("Can't rename '" .. old .. "' -> '" .. new .. "': " .. b);
	end
end

----------------------------------------------------------------------------------------------------------------------
-- Function compute_max_div(Integer ctsBound, Integer timescale, Integer maxCode, pictureOffset)
-- Compute maximum allowable timescale.
----------------------------------------------------------------------------------------------------------------------
compute_max_div = function(ctsBound, timeScale, maxCode, pictureOffset)
	-- Compute the logical number of frames.
	local logicalFrames = ctsBound / pictureOffset;
	local maxNumerator = math.floor(maxCode / logicalFrames);
	-- Be conservative and assume numerator is rounded up. That is, solve the biggest maxdiv such that for all
	-- 1 <= x <= maxdiv, maxNumerator >= ceil(x * pictureOffset / timeScale) is true.
	-- Since maxNumerator is integer, this is equivalent to:
	-- maxNumerator >= x * pictureOffset / timeScale
	-- => maxNumerator * timeScale / pictureOffset >= x, thus
	-- maxDiv = math.floor(maxNumerator * timeScale / pictureOffset);
	return math.floor(maxNumerator * timeScale / pictureOffset);
end

----------------------------------------------------------------------------------------------------------------------
-- Function rational_approximate(Integer origNum, Integer origDenum, Integer maxDenum)
-- Approximate origNum / origDenum using rational with maximum denumerator of maxDenum
----------------------------------------------------------------------------------------------------------------------
rational_approximate = function(origNum, origDenum, maxDenum)
	-- FIXME: Better approximations are possible.
	local div = math.ceil(origDenum / maxDenum);
	return math.floor(0.5 + origNum / div), math.floor(0.5 + origDenum / div);
end

----------------------------------------------------------------------------------------------------------------------
-- Function fixup_mp4box_bug_cfr(Table header, Table samples, Integer pictureOffset, Integer maxdiv)
-- Fix MP4Box timecode bug for CFR video by approximating the framerate a bit.
----------------------------------------------------------------------------------------------------------------------
fixup_mp4box_bug_cfr = function(header, samples, pictureOffset, maxdiv)
	local oNum, oDenum;
	local nNum, nDenum;
	local i;
	oNum, oDenum = pictureOffset, header.timeScale;
	nNum, nDenum = rational_approximate(oNum, oDenum, maxdiv);
	header.timeScale = nDenum;
	for i = 1, #samples do
		samples[i].DTS = math.floor(0.5 + samples[i].DTS / oNum * nNum);
		samples[i].CTS = math.floor(0.5 + samples[i].CTS / oNum * nNum);
	end
end


if #arg < 3 then
	error("Syntax: NHMLFixup.lua <video.nhml> <audio.nhml> <timecodes.txt> [delay=<delay>] [tvaspect|widescreen]");
end

-- Load the NHML files.
io.stdout:write("Loading '" .. arg[1] .. "'..."); io.stdout:flush();
video_header, video_samples = call_with_file(load_NHML, arg[1], "r");
io.stdout:write("Done.\n");
io.stdout:write("Loading '" .. arg[2] .. "'..."); io.stdout:flush();
audio_header, audio_samples = call_with_file(load_NHML, arg[2], "r");
io.stdout:write("Done.\n");
io.stdout:write("String to number conversion on video header..."); io.stdout:flush();
map_table_to_number(video_header);
io.stdout:write("Done.\n");
io.stdout:write("String to number conversion on video samples..."); io.stdout:flush();
map_fields_to_number(video_samples);
io.stdout:write("Done.\n");
io.stdout:write("String to number conversion on audio header..."); io.stdout:flush();
map_table_to_number(audio_header);
io.stdout:write("Done.\n");
io.stdout:write("String to number conversion on audio samples..."); io.stdout:flush();
map_fields_to_number(audio_samples);
io.stdout:write("Done.\n");
if video_header.streamType == 4 and audio_header.streamType == 5 then
	-- Ok.
elseif video_header.streamType == 5 and audio_header.streamType == 4 then
	print("WARNING: You got audio and video wrong way around. Swapping them for you...");
	audio_header,audio_samples,arg[2],video_header,video_samples,arg[1] =
		video_header,video_samples,arg[1],audio_header,audio_samples,arg[2];
else
	error("Expected one video track and one audio track");
end

if video_header.trackID == audio_header.trackID then
	print("WARNING: Audio and video have the same track id. Assigning new track id to audio track...");
	audio_header.trackID = audio_header.trackID + 1;
end

io.stdout:write("Computing CTS for video samples..."); io.stdout:flush();
translate_NHML_TS_in(video_samples, video_header.DTS_increment or 0);
io.stdout:write("Done.\n");
io.stdout:write("Computing CTS for audio samples..."); io.stdout:flush();
translate_NHML_TS_in(audio_samples, audio_header.DTS_increment or 0);
io.stdout:write("Done.\n");

-- Alter timescale if needed and load the timecode data.
delay = 0;
rdelay = 0;
for i = 4,#arg do
	if arg[i] == "tvaspect" then
		do_aspect_fixup = 1;
	elseif arg[i] == "widescreen" then
		do_aspect_fixup = 2;
	elseif string.sub(arg[i], 1, 6) == "delay=" then
		local n = tonumber(string.sub(arg[i], 7, #(arg[i])));
		if not n then
			error("Bad delay.");
		end
		rdelay = n;
		delay = math.floor(0.5 + rdelay / 1000 * video_header.timeScale);
	end
end


MAX_MP4BOX_TIMECODE = 0x7FFFFFF;
if arg[3] ~= "@CFR" then
	timecode_data = call_with_file(load_timecode_file, arg[3], "r", video_header.timeScale);
	if timecode_data[#timecode_data] > MAX_MP4BOX_TIMECODE then
		-- Workaround MP4Box bug.
		divider = math.ceil(timecode_data[#timecode_data] / MAX_MP4BOX_TIMECODE);
		print("Notice: Dividing timecodes by " .. divider .. " to workaround MP4Box timecode bug.");
		io.stdout:write("Performing division..."); io.stdout:flush();
		video_header.timeScale = math.floor(0.5 + video_header.timeScale / divider);
		for i = 1,#timecode_data do
			timecode_data[i] = math.floor(0.5 + timecode_data[i] / divider);
		end
		--Recompute delay.
		delay = math.floor(0.5 + rdelay / 1000 * video_header.timeScale);
		io.stdout:write("Done.\n");
	end
else
	timecode_data = nil;
	local maxCTS = 0;
	local i;
	local DTSOffset = (video_samples[2] or video_samples[1]).DTS - video_samples[1].DTS;
	if DTSOffset == 0 then
		DTSOffset = 1;
	end
	for i = 1,#video_samples do
		if video_samples[i].CTS > maxCTS then
			maxCTS = video_samples[i].CTS;
		end
		if video_samples[i].DTS % DTSOffset ~= 0 then
			error("Video is not CFR");
		end
		if (video_samples[i].CTS - video_samples[1].CTS) % DTSOffset ~= 0 then
			error("Video is not CFR");
		end
	end
	if video_samples[#video_samples].CTS > MAX_MP4BOX_TIMECODE then
		--Workaround MP4Box bug.
		local maxdiv = compute_max_div(maxCTS, video_header.timeScale, MAX_MP4BOX_TIMECODE, DTSOffset);
		print("Notice: Restricting denumerator to " .. maxdiv .. " to workaround MP4Box timecode bug.");
		io.stdout:write("Fixing timecodes..."); io.stdout:flush();
		fixup_mp4box_bug_cfr(video_header, video_samples, DTSOffset, maxdiv);
		--Recompute delay.
		delay = math.floor(0.5 + rdelay / 1000 * video_header.timeScale);
		io.stdout:write("Done.\n");
	end
end

-- Do the actual fixup.
io.stdout:write("Fixing up video timecodes..."); io.stdout:flush();
audio_fixup = fixup_video_times(video_samples, timecode_data, delay);
io.stdout:write("Done.\n");
io.stdout:write("Fixing up audio timecodes..."); io.stdout:flush();
fixup_audio_times(audio_samples, audio_fixup, video_header.timeScale, audio_header.timeScale);
io.stdout:write("Done.\n");

if do_aspect_fixup == 1 then
	video_header.parNum, video_header.parDen = reduce_fraction(4 * video_header.height, 3 * video_header.width);
end
if do_aspect_fixup == 2 then
	video_header.parNum, video_header.parDen = reduce_fraction(16 * video_header.height, 9 * video_header.width);
end

-- Save the NHML files.
io.stdout:write("Computing CTSOffset for video samples..."); io.stdout:flush();
translate_NHML_TS_out(video_samples);
io.stdout:write("Done.\n");
io.stdout:write("Computing CTSOffset for audio samples..."); io.stdout:flush();
translate_NHML_TS_out(audio_samples);
io.stdout:write("Done.\n");
io.stdout:write("Saving '" .. arg[1] .. ".tmp'..."); io.stdout:flush();
call_with_file(write_NHML_data, arg[1] .. ".tmp", "w", video_header, video_samples);
io.stdout:write("Done.\n");
io.stdout:write("Saving '" .. arg[2] .. ".tmp'..."); io.stdout:flush();
call_with_file(write_NHML_data, arg[2] .. ".tmp", "w", audio_header, audio_samples);
io.stdout:write("Done.\n");
io.stdout:write("Renaming '" .. arg[1] .. ".tmp' -> '" .. arg[1] .. "'..."); io.stdout:flush();
rename_errcheck(arg[1] .. ".tmp", arg[1], arg[1] .. ".bak");
io.stdout:write("Done.\n");
io.stdout:write("Renaming '" .. arg[2] .. ".tmp' -> '" .. arg[2] .. "'..."); io.stdout:flush();
rename_errcheck(arg[2] .. ".tmp", arg[2], arg[2] .. ".bak");
io.stdout:write("Done.\n");
io.stdout:write("All done.\n");
