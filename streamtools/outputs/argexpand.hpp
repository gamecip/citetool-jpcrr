#ifndef _outputs__argexpand__hpp__included__
#define _outputs__argexpand__hpp__included__

#include <string>

std::string expand_arguments_common(std::string spec, std::string commaexpand, std::string equalsexpand,
	std::string& executable);

#endif
