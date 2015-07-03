#include "outputs/argexpand.hpp"
#include <sstream>

std::string expand_arguments_common(std::string opts, std::string commaexpand, std::string equalsexpand,
	std::string& executable)
{
	bool insert = true;
	bool first = true;
	bool escape = false;
	size_t epos = 0;
	std::ostringstream ret;
	if(opts.find("executable=") == 0) {
		//Strip the first.
		size_t s = opts.find_first_of(",");
		if(s >= opts.length()) {
			executable = opts.substr(11);
			return " ";
		} else {
			executable = opts.substr(11, s - 11);
			opts = opts.substr(s + 1);
		}
	} else if((epos = opts.find(",executable=")) < opts.length()) {
		size_t s = opts.find_first_of(",", epos + 1);
		if(s >= opts.length()) {
			executable = opts.substr(epos + 12);
			opts = opts.substr(0, epos);
		} else {
			executable = opts.substr(epos + 12, s - (epos + 12));
			opts = opts.substr(0, epos) + opts.substr(s);
		}
	}
	for(size_t i = 0; i < opts.length(); i++) {
		if(escape) {
			ret << opts[i];
			escape = false;
			continue;
		}
		if(insert) {
			if(first)
				ret << commaexpand;
			else
				ret << " " << commaexpand;
		}
		first = false;
		insert = false;
		switch(opts[i]) {
		case '\\':
			escape = true;
			break;
		case ',':
			insert = true;
			break;
		case '=':
			ret << equalsexpand;
			break;
		default:
			ret << opts[i];
		};
	}
	ret << " ";
	return ret.str();
}
